package io.github.profetgit.cyanotype.ghost;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import io.github.profetgit.cyanotype.Cyanotype;
import io.github.profetgit.cyanotype.blueprint.Blueprint;
import io.github.profetgit.cyanotype.placement.Placement;
import io.github.profetgit.cyanotype.placement.Placements;
import io.github.profetgit.cyanotype.verify.Matcher;
import io.github.profetgit.cyanotype.verify.Verifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Draws the ghosts of every placement. Everything here runs on the render thread except the bake jobs. Each frame:
 * ease each placement's drawn position, notice a changed level or resource reload (rebuild), keep a ghost baked for
 * where each placement really is (a new one is baked beside the old and swapped in when it is ready, so edits never
 * flicker), start bake jobs for the nearest unbaked sections, upload finished meshes (bounded per frame), then draw
 * every visible uploaded section in one render pass, nearest first, with the block pipeline's translucent variant so
 * fog, lightmap and shader packs treat it as terrain.
 */
public final class GhostRenderer {
    /** One placement's ghost: the one on screen, and the next one while it bakes. */
    private static final class Slot {
        Ghost current, pending;
    }

    private static final Map<Placement, Slot> SLOTS = new IdentityHashMap<>();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4)), r -> {
        Thread t = new Thread(r, "Cyanotype baker");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final int MAX_IN_FLIGHT = 8;
    private static final int UPLOAD_BYTES_PER_FRAME = 4 << 20;
    /** A placement that has not moved for this long gets a ghost baked for its true position (tints and offsets depend on it). */
    private static final long REBAKE_AFTER_NS = 250_000_000L;
    /** Ghosts start to fade out this far into the draw range (as a share of it). */
    private static final double FADE_FROM = 0.55;
    /** Whether placements that are locked are compared with the world (the Verifier). */
    public static volatile boolean verifyEnabled = true;
    /** Whether ghosts fade out toward the edge of the draw range. */
    public static volatile boolean fadeEnabled = true;
    private static Object lastModels;
    private static long lastFrameNs;

    /** The quick toggle: ghosts keep baking but are not drawn (nor are their handles). */
    public static volatile boolean hidden;

    /** How far from the camera sections are baked and drawn, in blocks. */
    public static volatile double range = 192;
    /** Colour the ghost is tinted with before opacity; white leaves the block textures as they are. */
    public static volatile float tintR = 0.80f, tintG = 0.93f, tintB = 1.0f;

    /** Dev counters, read by the demo and the perf lab. */
    public static final class Stats {
        public static volatile int sections, uploaded, drawn, quadsDrawn;
        public static final LongAdder bytesUploaded = new LongAdder(), bakeNanos = new LongAdder(), bakedSections = new LongAdder();
        public static volatile long cpuNanos, frames;
    }

    private GhostRenderer() {
    }

    /** Frees every ghost; the next frame rebuilds those that should be drawn. */
    public static void clear() {
        for (Slot s : SLOTS.values()) disposeSlot(s);
        SLOTS.clear();
    }

    /** Ghosts currently on screen, one per drawn placement (dev checks). */
    public static List<Ghost> ghosts() {
        List<Ghost> out = new ArrayList<>();
        for (Slot s : SLOTS.values()) if (s.current != null) out.add(s.current);
        return out;
    }

    /** The drawn offset of a ghost from where it was baked, as the renderer applies it. */
    private static void shift(Placement p, Ghost g, double[] out) {
        double y = p.vy + liftOffset(p) - g.bakeOrigin.getY();
        if (g.bakeOrientation.equals(p.orientation)) {
            out[0] = p.vx - g.bakeOrigin.getX();
            out[2] = p.vz - g.bakeOrigin.getZ();
        } else {
            // a turned placement still shows its old ghost for a moment: keep it centred where the new one will be
            double cx = p.vx + p.sizeX() / 2.0, cz = p.vz + p.sizeZ() / 2.0;
            out[0] = cx - (g.bakeOrigin.getX() + g.sizeX() / 2.0);
            out[2] = cz - (g.bakeOrigin.getZ() + g.sizeZ() / 2.0);
        }
        out[1] = y;
    }

    /** Whether the placement has a ghost on screen right now. */
    public static boolean drawn(Placement p) {
        Slot s = SLOTS.get(p);
        return s != null && s.current != null;
    }

    /** The height the placement is drawn at: its eased position plus the lift of a held ghost. */
    public static double visualY(Placement p) {
        return p.vy + liftOffset(p);
    }

    /** How far above its place the ghost is drawn: held while following the crosshair, a spring after the lock. */
    static double liftOffset(Placement p) {
        if (!p.locked && Placements.active() == p && Placements.mode() == Placements.Mode.PLACING) return Feel.LIFT;
        if (p.settleStartNs != 0) {
            double t = (System.nanoTime() - p.settleStartNs) / 1e9;
            if (t >= Feel.SETTLE_SECONDS) {
                p.settleStartNs = 0;
                return 0;
            }
            return Feel.LIFT * Feel.spring(t);
        }
        return 0;
    }

    /** Whether every ghost has finished baking what is in range and has swapped in its latest version (dev checks). */
    public static boolean settled() {
        boolean any = false;
        for (Placement p : Placements.all()) {
            if (!wantsDraw(p, Minecraft.getInstance().level)) continue;
            any = true;
            Slot s = SLOTS.get(p);
            if (s == null || s.current == null || s.pending != null) return false;
            if (s.current.sections == null || s.current.pendingInRange != 0) return false;
            if (!s.current.bakeOrientation.equals(p.orientation)) return false;
        }
        return any;
    }

    static boolean wantsDraw(Placement p, ClientLevel level) {
        return level != null && p.visible && p.ready() && p.dimension.equals(level.dimension().identifier().toString());
    }

    private static void disposeSlot(Slot s) {
        if (s.current != null) s.current.dispose();
        if (s.pending != null) s.pending.dispose();
        s.current = s.pending = null;
    }

    private static Ghost newGhost(Placement p, Blueprint bp, ClientLevel level) {
        Ghost g = new Ghost(p, bp, level, p.origin, p.orientation);
        WORKERS.execute(() -> {
            try {
                g.prepare();
            } catch (Throwable t) {
                Cyanotype.LOG.error("Could not prepare ghost of {}", p.name, t);
            }
        });
        return g;
    }

    /** Called once per frame from the level renderer, after the main pass has composed the scene. */
    public static void render(Minecraft mc, CameraRenderState cam, RenderTarget target) {
        long t0 = System.nanoTime();
        try {
            renderTimed(mc, cam, target);
        } finally {
            if (!SLOTS.isEmpty()) {
                Stats.cpuNanos += System.nanoTime() - t0;
                Stats.frames++;
            }
        }
    }

    private static void renderTimed(Minecraft mc, CameraRenderState cam, RenderTarget target) {
        ClientLevel level = mc.level;
        long now = System.nanoTime();
        double dt = lastFrameNs == 0 ? 0.016 : Math.min(0.1, (now - lastFrameNs) / 1e9);
        lastFrameNs = now;

        Object models = mc.getModelManager().getBlockStateModelSet();
        boolean reloaded = lastModels != null && models != lastModels;
        lastModels = models;
        if (reloaded) clear();
        // placements that are gone, or whose ghost belongs to another level
        SLOTS.entrySet().removeIf(e -> {
            Placement p = e.getKey();
            Slot s = e.getValue();
            boolean gone = !Placements.all().contains(p);
            boolean otherLevel = (s.current != null && s.current.level != level) || (s.pending != null && s.pending.level != level);
            if (gone || otherLevel || !wantsDraw(p, level)) {
                disposeSlot(s);
                return true;
            }
            return false;
        });

        for (Placement p : Placements.all()) {
            if (!wantsDraw(p, level)) continue;
            animate(p, dt);
            maintain(p, SLOTS.computeIfAbsent(p, k -> new Slot()), p.blueprint, level, now);
        }
        if (level == null || SLOTS.isEmpty()) return;

        double cx = cam.pos.x, cy = cam.pos.y, cz = cam.pos.z;
        double rangeSq = range * range;
        double[] sh = new double[3];
        List<Ghost.Section> candidates = new ArrayList<>();
        List<double[]> shifts = new ArrayList<>();
        int total = 0;
        for (Slot s : SLOTS.values()) {
            for (Ghost g : new Ghost[]{s.pending, s.current}) {
                if (g == null || g.sections == null) continue;
                shift(g.placement, g, sh);
                double[] mine = sh.clone();
                int pending = 0;
                Verifier v = g.verifier;
                for (Ghost.Section sec : g.sections) {
                    if (distSq(sec.bounds, mine, cx, cy, cz) > rangeSq) continue;
                    // the world changed under a section that is up to date: bake it again, drawing the old mesh meanwhile
                    if (v != null && sec.state == Ghost.Section.UPLOADED && v.version(sec.part, sec.vsec) != sec.bakedVersion) sec.state = Ghost.Section.IDLE;
                    if (sec.state == Ghost.Section.IDLE || sec.state == Ghost.Section.BAKING || sec.state == Ghost.Section.BAKED) pending++;
                    candidates.add(sec);
                    shifts.add(mine);
                }
                g.pendingInRange = pending;
                if (g == s.current) total += g.sections.size();
            }
            if (s.current != null && s.current.sections == null) s.current.pendingInRange = -1;
            if (s.pending != null && s.pending.sections == null) s.pending.pendingInRange = -1;
        }
        Stats.sections = total;

        // nearest first, for baking
        Integer[] order = new Integer[candidates.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final double[] cc = {cx, cy, cz};
        java.util.Arrays.sort(order, Comparator.comparingDouble(i -> distSq(candidates.get(i).bounds, shifts.get(i), cc[0], cc[1], cc[2])));

        for (int i : order) {
            if (IN_FLIGHT.get() >= MAX_IN_FLIGHT) break;
            Ghost.Section sec = candidates.get(i);
            if (sec.state != Ghost.Section.IDLE) continue;
            // a verified ghost is first baked once the verifier has looked at that part of the world, so it never shows what is already built
            if (sec.ghost.verifier != null && !sec.ghost.verifier.ready(sec.part, sec.vsec)) continue;
            startBake(sec);
        }
        int budget = UPLOAD_BYTES_PER_FRAME;
        for (int i : order) {
            Ghost.Section sec = candidates.get(i);
            if (sec.state != Ghost.Section.BAKED) continue;
            if (budget <= 0) break;
            budget -= upload(sec);
        }

        if (hidden) {
            Stats.drawn = 0;
            Stats.quadsDrawn = 0;
            return;
        }
        List<Ghost.Section> draw = new ArrayList<>();
        List<double[]> drawShift = new ArrayList<>();
        List<double[]> drawRange = new ArrayList<>();
        double fadeStart = range * FADE_FROM;
        for (int i : order) {
            Ghost.Section sec = candidates.get(i);
            if (sec.buffer == null || sec.ghost.disposed) continue;
            // only what is on screen draws: a pending ghost waits until it swaps in
            if (!isCurrent(sec.ghost)) continue;
            Placement pl = sec.ghost.placement;
            // layer focus: a range of layers is a range of quads
            int base = sec.region.oy + sec.y;
            int a = pl.layerLo < 0 ? 0 : Math.max(0, pl.layerLo - base);
            int b = pl.layerHi < 0 ? sec.h - 1 : Math.min(sec.h - 1, pl.layerHi - base);
            if (a > b || sec.layerQuads == null) continue;
            int q0 = sec.layerQuads[a], q1 = sec.layerQuads[b + 1];
            if (q1 <= q0) continue;
            double[] m = shifts.get(i);
            AABB box = sec.bounds.move(m[0], m[1], m[2]);
            if (!cam.cullFrustum.isVisible(box)) continue;
            double dist = Math.sqrt(distSq(sec.bounds, m, cx, cy, cz));
            double fade = !fadeEnabled || dist <= fadeStart ? 1.0 : Math.max(0.0, 1.0 - (dist - fadeStart) / (range - fadeStart));
            if (fade < 0.03) continue;
            draw.add(sec);
            drawShift.add(m);
            drawRange.add(new double[]{q0, q1, fade});
        }
        Stats.drawn = draw.size();
        if (draw.isEmpty()) {
            Stats.quadsDrawn = 0;
            return;
        }
        draw(cam, target, draw, drawShift, drawRange);
    }

    private static boolean isCurrent(Ghost g) {
        Slot s = SLOTS.get(g.placement);
        return s != null && s.current == g;
    }

    /** Eases the drawn position toward the real one. */
    private static void animate(Placement p, double dt) {
        if (!p.visualReady) {
            p.vx = p.origin.getX();
            p.vy = p.origin.getY();
            p.vz = p.origin.getZ();
            p.visualReady = true;
            return;
        }
        p.vx = Feel.ease(p.vx, p.origin.getX(), dt, Feel.FOLLOW);
        p.vy = Feel.ease(p.vy, p.origin.getY(), dt, Feel.FOLLOW);
        p.vz = Feel.ease(p.vz, p.origin.getZ(), dt, Feel.FOLLOW);
    }

    /** Whether a ghost still serves a placement: same blueprint and turn, and baked where it is (or still moving, when the offset hides the difference). */
    private static boolean fits(Ghost g, Placement p, Blueprint bp, boolean idle) {
        return g.blueprint == bp && g.bakeOrientation.equals(p.orientation) && (g.bakeOrigin.equals(p.origin) || !idle);
    }

    /** Keeps a ghost baked for where the placement really is, and swaps a finished one in. */
    private static void maintain(Placement p, Slot s, Blueprint bp, ClientLevel level, long now) {
        boolean idle = now - p.lastMoveNs >= REBAKE_AFTER_NS;
        Ghost newest = s.pending != null ? s.pending : s.current;
        if (newest == null || !fits(newest, p, bp, idle)) {
            // a newer wish than what is baking or showing: start over for it, keeping what is on screen until it is ready
            if (s.pending != null) s.pending.dispose();
            s.pending = newGhost(p, bp, level);
        }
        Ghost pend = s.pending;
        if (pend != null && pend.sections != null && pend.pendingInRange == 0) {
            if (s.current != null) s.current.dispose();
            s.current = pend;
            s.pending = null;
        }
        attachVerifier(p, s.current);
        attachVerifier(p, s.pending);
    }

    /** A locked placement's ghost, once it is baked for where the placement really is, gets a verifier. */
    private static void attachVerifier(Placement p, Ghost g) {
        if (g == null || g.verifier != null || g.sections == null || g.regions == null) return;
        if (!verifyEnabled || !p.locked || !g.matches(p)) return;
        g.verifier = new Verifier(g.regions, g.bakeOrigin.getX(), g.bakeOrigin.getY(), g.bakeOrigin.getZ(), Matcher.LENIENT);
    }

    /** The verifier of the ghost that is on screen for a placement, or null while it is being placed or not yet baked. */
    public static Verifier verifierOf(Placement p) {
        Slot s = SLOTS.get(p);
        if (s == null) return null;
        if (s.current != null && s.current.verifier != null) return s.current.verifier;
        return s.pending == null ? null : s.pending.verifier;
    }

    /** A block changed in the client's world: every verifier that covers it is told. */
    public static void onBlockChanged(long packedPos) {
        for (Slot s : SLOTS.values()) {
            if (s.current != null && s.current.verifier != null) s.current.verifier.markDirty(packedPos);
            if (s.pending != null && s.pending.verifier != null) s.pending.verifier.markDirty(packedPos);
        }
    }

    /** Called every client tick: lets each verifier do a slice of work, about a millisecond and a half in all. */
    public static void tickVerifiers(Minecraft mc) {
        if (mc.level == null || mc.player == null || SLOTS.isEmpty()) return;
        int active = 0;
        for (Slot s : SLOTS.values()) {
            if (s.current != null && s.current.verifier != null) active++;
            if (s.pending != null && s.pending.verifier != null) active++;
        }
        if (active == 0) return;
        ClientWorldView view = new ClientWorldView(mc.level);
        long budget = Math.max(200_000L, 1_500_000L / active);
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        for (Slot s : SLOTS.values()) {
            for (Ghost g : new Ghost[]{s.current, s.pending}) {
                if (g != null && g.verifier != null) g.verifier.process(view, budget, px, py, pz);
            }
        }
    }

    private static double distSq(AABB b, double[] m, double cx, double cy, double cz) {
        double dx = Math.max(Math.max(b.minX + m[0] - cx, 0), cx - (b.maxX + m[0]));
        double dy = Math.max(Math.max(b.minY + m[1] - cy, 0), cy - (b.maxY + m[1]));
        double dz = Math.max(Math.max(b.minZ + m[2] - cz, 0), cz - (b.maxZ + m[2]));
        return dx * dx + dy * dy + dz * dz;
    }

    private static void startBake(Ghost.Section s) {
        s.state = Ghost.Section.BAKING;
        // what the world looked like when this bake started; a later change bumps the version and bakes the section again
        Verifier v = s.ghost.verifier;
        byte[] mask = v == null ? null : v.snapshot(s.part, s.x, s.y, s.z, s.w, s.h, s.d);
        s.bakingVersion = v == null ? 0 : v.version(s.part, s.vsec);
        IN_FLIGHT.incrementAndGet();
        WORKERS.execute(() -> {
            long t0 = System.nanoTime();
            try {
                if (s.ghost.disposed) {
                    s.state = Ghost.Section.IDLE;
                    return;
                }
                // null: nothing to draw in this box (all of it built, or empty); the section still counts as baked
                SectionMesher.Baked b = SectionMesher.bake(s.region, s.wx, s.wy, s.wz, s.x, s.y, s.z, s.w, s.h, s.d, s.ghost.level, mask);
                s.baked = b;
                s.state = Ghost.Section.BAKED;
                if (s.ghost.disposed && b != null) s.release();
            } catch (Throwable t) {
                Cyanotype.LOG.error("Ghost section bake failed", t);
                s.state = Ghost.Section.FAILED;
            } finally {
                Stats.bakeNanos.add(System.nanoTime() - t0);
                Stats.bakedSections.increment();
                IN_FLIGHT.decrementAndGet();
            }
        });
    }

    /** @return bytes uploaded */
    private static int upload(Ghost.Section s) {
        SectionMesher.Baked b = s.baked;
        s.baked = null;
        s.bakedVersion = s.bakingVersion;
        if (b == null) {
            // nothing to show: drop any old buffer
            if (s.buffer != null) s.buffer.close();
            s.buffer = null;
            s.indexCount = 0;
            s.quads = 0;
            s.layerQuads = null;
            s.state = Ghost.Section.UPLOADED;
            return 0;
        }
        int bytes = b.mesh().vertexBuffer().remaining();
        try {
            GpuBuffer fresh = RenderSystem.getDevice().createBuffer(() -> "Cyanotype ghost section", GpuBuffer.USAGE_VERTEX, b.mesh().vertexBuffer());
            if (s.buffer != null) s.buffer.close();
            s.buffer = fresh;
            s.indexCount = b.mesh().drawState().indexCount();
            s.quads = b.quads();
            s.layerQuads = b.layerQuads();
            RenderSystem.getSequentialBuffer(SectionMesher.renderType().primitiveTopology()).requestIndexCount(s.indexCount);
            s.state = Ghost.Section.UPLOADED;
            Stats.uploaded++;
            Stats.bytesUploaded.add(bytes);
        } finally {
            b.close();
        }
        return bytes;
    }

    private static void draw(CameraRenderState cam, RenderTarget target, List<Ghost.Section> sections, List<double[]> shifts, List<double[]> ranges) {
        PreparedRenderType prepared = SectionMesher.renderType().prepare();
        RenderPipeline pipeline = prepared.pipeline();
        Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
        Matrix4f texture = new Matrix4f();
        var index = RenderSystem.getSequentialBuffer(SectionMesher.renderType().primitiveTopology());
        int quads = 0;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "Cyanotype ghost", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            for (PreparedRenderType.Texture t : prepared.textures()) pass.setUniform(t.name(), t.textureView(), t.sampler());
            for (int i = 0; i < sections.size(); i++) {
                Ghost.Section s = sections.get(i);
                double[] m = shifts.get(i);
                double[] qr = ranges.get(i);
                float opacity = (float) (s.ghost.placement.opacity * qr[2]);
                var slice = RenderSystem.getDynamicUniforms().writeTransform(
                    modelView,
                    new Vector4f(tintR, tintG, tintB, opacity),
                    new Vector3f((float) (s.wx + s.x + m[0] - cam.pos.x), (float) (s.wy + s.y + m[1] - cam.pos.y), (float) (s.wz + s.z + m[2] - cam.pos.z)),
                    texture);
                pass.setUniform("DynamicTransforms", slice);
                pass.setVertexBuffer(0, s.buffer.slice());
                pass.setIndexBuffer(index.getBuffer(s.indexCount), index.type());
                int q0 = (int) qr[0], q1 = (int) qr[1];
                pass.drawIndexed((q1 - q0) * 6, 1, q0 * 6, 0, 0);
                quads += q1 - q0;
            }
        }
        Stats.quadsDrawn = quads;
    }
}
