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
                for (Ghost.Section sec : g.sections) {
                    if (distSq(sec.bounds, mine, cx, cy, cz) > rangeSq) continue;
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

        // nearest first, for baking; the pending ghosts bake after what is on screen only when nothing else is waiting
        Integer[] order = new Integer[candidates.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final double[] cc = {cx, cy, cz};
        java.util.Arrays.sort(order, Comparator.comparingDouble(i -> distSq(candidates.get(i).bounds, shifts.get(i), cc[0], cc[1], cc[2])));

        for (int i : order) {
            if (IN_FLIGHT.get() >= MAX_IN_FLIGHT) break;
            Ghost.Section sec = candidates.get(i);
            if (sec.state == Ghost.Section.IDLE) startBake(sec);
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
        for (int i : order) {
            Ghost.Section sec = candidates.get(i);
            if (sec.state != Ghost.Section.UPLOADED || sec.ghost.disposed) continue;
            // only what is on screen draws: a pending ghost waits until it swaps in
            if (!isCurrent(sec.ghost)) continue;
            double[] m = shifts.get(i);
            AABB box = sec.bounds.move(m[0], m[1], m[2]);
            if (cam.cullFrustum.isVisible(box)) {
                draw.add(sec);
                drawShift.add(m);
            }
        }
        Stats.drawn = draw.size();
        if (draw.isEmpty()) {
            Stats.quadsDrawn = 0;
            return;
        }
        draw(cam, target, draw, drawShift);
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
    }

    private static double distSq(AABB b, double[] m, double cx, double cy, double cz) {
        double dx = Math.max(Math.max(b.minX + m[0] - cx, 0), cx - (b.maxX + m[0]));
        double dy = Math.max(Math.max(b.minY + m[1] - cy, 0), cy - (b.maxY + m[1]));
        double dz = Math.max(Math.max(b.minZ + m[2] - cz, 0), cz - (b.maxZ + m[2]));
        return dx * dx + dy * dy + dz * dz;
    }

    private static void startBake(Ghost.Section s) {
        s.state = Ghost.Section.BAKING;
        IN_FLIGHT.incrementAndGet();
        WORKERS.execute(() -> {
            long t0 = System.nanoTime();
            try {
                if (s.ghost.disposed) {
                    s.state = Ghost.Section.IDLE;
                    return;
                }
                SectionMesher.Baked b = SectionMesher.bake(s.region, s.wx, s.wy, s.wz, s.x, s.y, s.z, s.w, s.h, s.d, s.ghost.level);
                if (b == null) {
                    s.state = Ghost.Section.EMPTY;
                } else {
                    s.baked = b;
                    s.state = Ghost.Section.BAKED;
                    if (s.ghost.disposed) s.release();
                }
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
        if (b == null) return 0;
        int bytes = b.mesh().vertexBuffer().remaining();
        try {
            s.buffer = RenderSystem.getDevice().createBuffer(() -> "Cyanotype ghost section", GpuBuffer.USAGE_VERTEX, b.mesh().vertexBuffer());
            s.indexCount = b.mesh().drawState().indexCount();
            s.quads = b.quads();
            RenderSystem.getSequentialBuffer(SectionMesher.renderType().primitiveTopology()).requestIndexCount(s.indexCount);
            s.state = Ghost.Section.UPLOADED;
            Stats.uploaded++;
            Stats.bytesUploaded.add(bytes);
        } finally {
            b.close();
        }
        return bytes;
    }

    private static void draw(CameraRenderState cam, RenderTarget target, List<Ghost.Section> sections, List<double[]> shifts) {
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
                float opacity = s.ghost.placement.opacity;
                var slice = RenderSystem.getDynamicUniforms().writeTransform(
                    modelView,
                    new Vector4f(tintR, tintG, tintB, opacity),
                    new Vector3f((float) (s.wx + s.x + m[0] - cam.pos.x), (float) (s.wy + s.y + m[1] - cam.pos.y), (float) (s.wz + s.z + m[2] - cam.pos.z)),
                    texture);
                pass.setUniform("DynamicTransforms", slice);
                pass.setVertexBuffer(0, s.buffer.slice());
                pass.setIndexBuffer(index.getBuffer(s.indexCount), index.type());
                pass.drawIndexed(s.indexCount, 1, 0, 0, 0);
                quads += s.quads;
            }
        }
        Stats.quadsDrawn = quads;
    }
}
