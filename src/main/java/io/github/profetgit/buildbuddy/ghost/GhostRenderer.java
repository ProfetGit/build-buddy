package io.github.profetgit.buildbuddy.ghost;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import io.github.profetgit.buildbuddy.BuildBuddy;
import io.github.profetgit.buildbuddy.blueprint.Blueprint;
import io.github.profetgit.buildbuddy.placement.Placement;
import io.github.profetgit.buildbuddy.placement.Placements;
import io.github.profetgit.buildbuddy.verify.Matcher;
import io.github.profetgit.buildbuddy.verify.Verifier;
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
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
        Thread t = new Thread(r, "Build Buddy baker");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final int MAX_IN_FLIGHT = 8;
    private static final int UPLOAD_BYTES_PER_FRAME = 4 << 20;
    /** A placement that has not moved for this long gets a ghost baked for its true position (tints and offsets depend on it). */
    /** Opacity of the blocks that stand in water, as a multiple of the ghost's own (capped at 1): the water drawn over them thins them out. */
    static final double WET_BOOST = 1.7;
    private static final long REBAKE_AFTER_NS = 250_000_000L;
    /** Ghosts start to fade out this far into the draw range (as a share of it). */
    private static final double FADE_FROM = 0.55;
    /** Whether placements that are locked are compared with the world (the Verifier). */
    public static volatile boolean verifyEnabled = true;
    /** Whether ghosts fade out toward the edge of the draw range. */
    public static volatile boolean fadeEnabled = true;
    private static Object lastModels;
    private static long lastFrameNs;
    /** The vertex layout the ghost's pipeline expects now: a shader pack on or off changes it, and meshes made for another layout must not be drawn. */
    private static VertexFormat liveFormat;
    /** Render failures in a row, and when drawing may start again after too many. */
    private static int failStreak;
    private static long pausedUntilNs, lastFailLogNs;

    /** The quick toggle: ghosts keep baking but are not drawn (nor are their handles). */
    public static volatile boolean hidden;

    /** How far from the camera sections are baked and drawn, in blocks. */
    public static volatile double range = 192;
    /** Colour the ghost is tinted with before opacity; white leaves the block textures as they are. */
    public static volatile float tintR = 0.80f, tintG = 0.93f, tintB = 1.0f;

    /** Dev counters, read by the demo and the perf lab. */
    public static final class Stats {
        public static volatile int sections, uploaded, drawn, quadsDrawn, capsDrawn;
        /** Dev: when set, every rendered frame adds {nanoTime, quadsDrawn, sections drawn, caps drawn}. */
        public static volatile java.util.List<long[]> record;
        public static final LongAdder bytesUploaded = new LongAdder(), bakeNanos = new LongAdder(), bakedSections = new LongAdder();
        public static volatile long cpuNanos, frames;
    }

    private GhostRenderer() {
    }

    /** Whether two vertex layouts are the same (the same elements in the same order): a shader pack can make a new object for the same layout. */
    static boolean sameFormat(VertexFormat a, VertexFormat b) {
        return a == b || a != null && b != null && a.toString().equals(b.toString());
    }

    /** Uploaded meshes written for a layout other than the one drawn now (dev checks; 0 once they have been made again). */
    public static int staleSections() {
        int n = 0;
        if (liveFormat == null) return 0;
        for (Slot s : SLOTS.values()) {
            for (Ghost g : new Ghost[]{s.current, s.pending}) {
                if (g == null || g.sections == null) continue;
                for (Ghost.Section sec : g.sections) if (sec.buffer != null && sec.meshFormat != null && !sameFormat(sec.meshFormat, liveFormat)) n++;
            }
        }
        return n;
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
                BuildBuddy.LOG.error("Could not prepare ghost of {}", p.name, t);
            }
        });
        return g;
    }

    /** What this frame draws, decided in {@link #prepare}: the blocks standing in water go in before the water, the rest after everything. */
    private static List<DrawItem> earlyItems = List.of(), lateItems = List.of();
    private static int earlyQuads, lateQuads;
    private static boolean prepared, earlyDrawn;

    /**
     * Called once per frame from the level renderer, before any pass: moves the ghosts, bakes, uploads and decides what
     * is drawn. The draws come later ({@link #drawEarly}, {@link #render}).
     */
    public static void prepare(Minecraft mc, CameraRenderState cam) {
        long t0 = System.nanoTime();
        earlyItems = lateItems = List.of();
        earlyQuads = lateQuads = 0;
        earlyDrawn = false;
        prepared = true;
        Stats.quadsDrawn = 0;
        if (t0 < pausedUntilNs) return;
        try {
            prepareTimed(mc, cam);
            failStreak = 0;
        } catch (Throwable t) {
            failed(t0, t, "prepared");
        } finally {
            if (!SLOTS.isEmpty()) Stats.cpuNanos += System.nanoTime() - t0;
        }
    }

    /**
     * Draws the blocks that stand in water into the pass that is about to draw the translucent features, so the water
     * (drawn after them) blends over them like it does over real blocks and tints them.
     */
    public static void drawEarly(RenderPass pass) {
        if (earlyItems.isEmpty() || earlyDrawn || System.nanoTime() < pausedUntilNs) return;
        long t0 = System.nanoTime();
        try {
            earlyDrawn = true;
            // the pass may have been opened by someone else (a shader pack's mod makes its own): bind what the pipeline reads
            RenderSystem.bindDefaultUniforms(pass);
            if (drawItems(pass, earlyItems)) Stats.quadsDrawn += earlyQuads;
        } catch (Throwable t) {
            failed(t0, t, "drawn before the water");
        } finally {
            Stats.cpuNanos += System.nanoTime() - t0;
        }
    }

    /** The same with improved transparency, where no pass is open before the water goes in: a pass of its own on the main target. */
    public static void drawEarly(RenderTarget target) {
        if (earlyItems.isEmpty() || earlyDrawn || System.nanoTime() < pausedUntilNs) return;
        long t0 = System.nanoTime();
        try {
            earlyDrawn = true;
            if (drawOwnPass(target, earlyItems)) Stats.quadsDrawn += earlyQuads;
        } catch (Throwable t) {
            failed(t0, t, "drawn before the water");
        } finally {
            Stats.cpuNanos += System.nanoTime() - t0;
        }
    }

    /** Called once per frame from the level renderer, after the main pass has composed the scene. */
    public static void render(Minecraft mc, CameraRenderState cam, RenderTarget target) {
        long t0 = System.nanoTime();
        if (!prepared) prepare(mc, cam);
        prepared = false;
        if (t0 < pausedUntilNs) return;
        try {
            // whatever was not drawn before the water (a pass that never came) is drawn now, as the whole ghost once was
            List<DrawItem> items = lateItems;
            int quads = lateQuads;
            if (!earlyDrawn && !earlyItems.isEmpty()) {
                items = new ArrayList<>(earlyItems);
                items.addAll(lateItems);
                quads += earlyQuads;
            }
            earlyDrawn = true;
            if (!items.isEmpty() && drawOwnPass(target, items)) Stats.quadsDrawn += quads;
            earlyItems = lateItems = List.of();
            failStreak = 0;
        } catch (Throwable t) {
            failed(t0, t, "drawn");
        } finally {
            java.util.List<long[]> rec = Stats.record;
            if (rec != null) rec.add(new long[]{t0, Stats.quadsDrawn, Stats.drawn, Stats.capsDrawn});
            if (!SLOTS.isEmpty()) {
                Stats.cpuNanos += System.nanoTime() - t0;
                Stats.frames++;
            }
        }
    }

    private static void failed(long t0, Throwable t, String what) {
        // the ghost is a guest in the frame: whatever goes wrong with it must not take the game down
        if (t0 - lastFailLogNs > 10_000_000_000L) {
            lastFailLogNs = t0;
            BuildBuddy.LOG.error("The ghost could not be " + what + " this frame", t);
        }
        if (++failStreak >= 3) {
            // something is broken for now (a pipeline being rebuilt, say): rest, throw the meshes away, start again clean
            pausedUntilNs = t0 + 2_000_000_000L;
            failStreak = 0;
            earlyItems = lateItems = List.of();
            try {
                clear();
            } catch (Throwable ignored) {
                SLOTS.clear();
            }
        }
    }

    private static void prepareTimed(Minecraft mc, CameraRenderState cam) {
        ClientLevel level = mc.level;
        long now = System.nanoTime();
        double dt = lastFrameNs == 0 ? 0.016 : Math.min(0.1, (now - lastFrameNs) / 1e9);
        lastFrameNs = now;

        Object models = mc.getModelManager().getBlockStateModelSet();
        boolean reloaded = lastModels != null && models != lastModels;
        lastModels = models;
        if (reloaded) clear();
        VertexFormat live = SectionMesher.renderType().format();
        if (liveFormat != null && !sameFormat(liveFormat, live)) BuildBuddy.LOG.info("The vertex layout changed (a shader pack was switched): ghost meshes are made again");
        liveFormat = live;
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
                    // a mesh written for another vertex layout cannot be drawn: it is made again (and not shown until it is)
                    if (sec.state == Ghost.Section.UPLOADED && sec.meshFormat != null && !sameFormat(sec.meshFormat, live)) sec.state = Ghost.Section.IDLE;
                    // a failed bake or upload is tried again after a while, a few times
                    if (sec.state == Ghost.Section.FAILED && sec.failures < 3 && now - sec.failedNs > 8_000_000_000L) sec.state = Ghost.Section.IDLE;
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

        // the Layers tool: the edge layers of the window need their cap meshes
        for (int i : order) {
            Ghost.Section sec = candidates.get(i);
            if (isCurrent(sec.ghost)) syncCaps(sec);
        }
        for (int i : order) {
            if (IN_FLIGHT.get() >= MAX_IN_FLIGHT) break;
            Ghost.Section sec = candidates.get(i);
            if (sec.state != Ghost.Section.IDLE) continue;
            // a verified ghost is first baked once the verifier has looked at that part of the world, so it never shows what is already built
            if (sec.ghost.verifier != null && !sec.ghost.verifier.ready(sec.part, sec.vsec)) continue;
            startBake(sec);
        }
        for (boolean exactOnly : new boolean[]{true, false}) {
            for (int i : order) {
                Ghost.Section sec = candidates.get(i);
                if (isCurrent(sec.ghost)) bakeCaps(sec, exactOnly);
            }
        }
        int budget = UPLOAD_BYTES_PER_FRAME;
        for (int i : order) {
            Ghost.Section sec = candidates.get(i);
            if (sec.state != Ghost.Section.BAKED) continue;
            if (budget <= 0) break;
            budget -= upload(sec);
        }
        for (int i : order) {
            Ghost.Section sec = candidates.get(i);
            for (Ghost.Cap c : sec.caps.values()) if (c.state == Ghost.Section.BAKED) uploadCap(c);
        }

        if (hidden) {
            Stats.drawn = 0;
            Stats.capsDrawn = 0;
            return;
        }
        List<DrawItem> early = new ArrayList<>(), late = new ArrayList<>();
        Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
        int sectionsDrawn = 0, capsDrawn = 0, eq = 0, lq = 0;
        double fadeStart = range * FADE_FROM;
        for (int i : order) {
            Ghost.Section sec = candidates.get(i);
            if (sec.buffer == null && sec.caps.isEmpty() || sec.ghost.disposed) continue;
            // only what is on screen draws: a pending ghost waits until it swaps in
            if (!isCurrent(sec.ghost)) continue;
            Placement pl = sec.ghost.placement;
            // layer focus: a range of layers is a range of quads
            int base = sec.region.oy + sec.y;
            int a = pl.layerLo < 0 ? 0 : Math.max(0, pl.layerLo - base);
            int b = pl.layerHi < 0 ? sec.h - 1 : Math.min(sec.h - 1, pl.layerHi - base);
            if (a > b) continue;
            double[] m = shifts.get(i);
            AABB box = sec.bounds.move(m[0], m[1], m[2]);
            if (!cam.cullFrustum.isVisible(box)) continue;
            double dist = Math.sqrt(distSq(sec.bounds, m, cx, cy, cz));
            double fade = !fadeEnabled || dist <= fadeStart ? 1.0 : Math.max(0.0, 1.0 - (dist - fadeStart) / (range - fadeStart));
            if (fade < 0.03) continue;
            boolean fits = sec.meshFormat == null || sameFormat(sec.meshFormat, live);
            if (fits && sec.buffer != null && sec.layerQuads != null) {
                int q0 = sec.layerQuads[a], q1 = sec.layerQuads[b + 1];
                boolean any = false;
                if (q1 > q0) {
                    late.add(item(sec, sec.buffer, sec.indexCount, m, q0, q1, fade, false, cam, modelView));
                    lq += q1 - q0;
                    any = true;
                }
                // the blocks standing in water, drawn before the water goes in
                if (sec.wetQuads != null) {
                    int w0 = sec.wetQuads[a], w1 = sec.wetQuads[b + 1];
                    if (w1 > w0) {
                        early.add(item(sec, sec.buffer, sec.indexCount, m, w0, w1, fade, true, cam, modelView));
                        eq += w1 - w0;
                        any = true;
                    }
                }
                if (any) sectionsDrawn++;
            }
            // the faces the cut layers would have covered
            for (Ghost.Cap c : sec.caps.values()) {
                if (c.exact && c.buffer != null && c.quads > 0 && (c.meshFormat == null || sameFormat(c.meshFormat, live))) {
                    late.add(item(sec, c.buffer, c.indexCount, m, 0, c.quads, fade, false, cam, modelView));
                    lq += c.quads;
                    capsDrawn++;
                }
            }
        }
        Stats.drawn = sectionsDrawn;
        Stats.capsDrawn = capsDrawn;
        earlyItems = early;
        lateItems = late;
        earlyQuads = eq;
        lateQuads = lq;
    }

    private static DrawItem item(Ghost.Section s, GpuBuffer buffer, int indexCount, double[] m, int q0, int q1, double fade, boolean wet, CameraRenderState cam, Matrix4f modelView) {
        double base = s.ghost.placement.opacity;
        // the water drawn over it takes most of what a ghost shows: stronger, so it reads like a block under water
        float opacity = (float) (Math.min(1.0, wet ? base * WET_BOOST : base) * fade);
        // written now, before any pass is open, like the game does for its own draws
        GpuBufferSlice slice = RenderSystem.getDynamicUniforms().writeTransform(
            modelView,
            new Vector4f(tintR, tintG, tintB, opacity),
            new Vector3f((float) (s.wx + s.x + m[0] - cam.pos.x), (float) (s.wy + s.y + m[1] - cam.pos.y), (float) (s.wz + s.z + m[2] - cam.pos.z)),
            new Matrix4f());
        return new DrawItem(buffer, indexCount, q0, q1, slice);
    }

    /** One buffer to draw: a section's mesh (a range of its quads) or one of its cap meshes. */
    private record DrawItem(GpuBuffer buffer, int indexCount, int q0, int q1, GpuBufferSlice transforms) {
    }

    // ---- caps for the Layers tool

    /** Decides which cap meshes a section needs for its placement's layer window, makes them, and drops the rest. */
    private static void syncCaps(Ghost.Section sec) {
        Placement pl = sec.ghost.placement;
        if (!pl.layered()) {
            if (!sec.caps.isEmpty()) {
                for (Ghost.Cap c : sec.caps.values()) c.release();
                sec.caps.clear();
            }
            return;
        }
        // a section that is being baked again (the world changed under it) keeps its caps: they draw until their own new mesh is up
        if (sec.state != Ghost.Section.UPLOADED && sec.bakedVersion < 0) return;
        int base = sec.region.oy + sec.y;
        java.util.Set<Integer> keep = new java.util.HashSet<>();
        if (pl.layerHi >= 0) wantCap(sec, true, pl.layerHi - base, keep);
        if (pl.layerLo > 0) wantCap(sec, false, pl.layerLo - base, keep);
        sec.caps.entrySet().removeIf(e -> {
            if (keep.contains(e.getKey())) return false;
            e.getValue().release();
            return true;
        });
        Verifier v = sec.ghost.verifier;
        if (v != null) {
            int version = v.version(sec.part, sec.vsec);
            for (Ghost.Cap c : sec.caps.values()) if (c.state == Ghost.Section.UPLOADED && c.bakedVersion != version) c.state = Ghost.Section.IDLE;
        }
        if (liveFormat != null) {
            for (Ghost.Cap c : sec.caps.values()) if (c.state == Ghost.Section.UPLOADED && c.meshFormat != null && !sameFormat(c.meshFormat, liveFormat)) c.state = Ghost.Section.IDLE;
        }
    }

    /** The cap at the window's edge layer (drawn) and the layers next to it (ready for when the window moves). */
    private static void wantCap(Ghost.Section sec, boolean top, int edge, java.util.Set<Integer> keep) {
        for (int dl = -1; dl <= 1; dl++) {
            int ly = edge + dl;
            if (ly < 0 || ly >= sec.h) continue;
            int key = ly * 2 + (top ? 1 : 0);
            keep.add(key);
            Ghost.Cap c = sec.caps.computeIfAbsent(key, k -> new Ghost.Cap(top, ly));
            c.exact = dl == 0;
        }
    }

    private static void bakeCaps(Ghost.Section sec, boolean exactOnly) {
        if (sec.caps.isEmpty()) return;
        Verifier v = sec.ghost.verifier;
        if (v != null && !v.ready(sec.part, sec.vsec)) return;
        for (Ghost.Cap c : sec.caps.values()) {
            if (c.state != Ghost.Section.IDLE || exactOnly && !c.exact) continue;
            if (IN_FLIGHT.get() >= MAX_IN_FLIGHT) return;
            startCapBake(sec, c);
        }
    }

    private static void startCapBake(Ghost.Section s, Ghost.Cap c) {
        c.state = Ghost.Section.BAKING;
        VertexFormat format = SectionMesher.renderType().format();
        Verifier v = s.ghost.verifier;
        byte[] mask = v == null ? null : v.snapshot(s.part, s.x, s.y, s.z, s.w, s.h, s.d);
        c.bakingVersion = v == null ? 0 : v.version(s.part, s.vsec);
        IN_FLIGHT.incrementAndGet();
        WORKERS.execute(() -> {
            try {
                if (s.ghost.disposed || c.dead) {
                    c.state = Ghost.Section.IDLE;
                    return;
                }
                SectionMesher.BakedCap b = SectionMesher.bakeCap(s.region, s.wx, s.wy, s.wz, s.x, s.y, s.z, s.w, s.h, s.d, c.layer, c.top, s.ghost.level, mask, format);
                c.baked = b;
                c.state = Ghost.Section.BAKED;
                if (c.dead || s.ghost.disposed) c.release();
            } catch (Throwable t) {
                BuildBuddy.LOG.error("Ghost cap bake failed", t);
                c.state = Ghost.Section.FAILED;
            } finally {
                IN_FLIGHT.decrementAndGet();
            }
        });
    }

    private static void uploadCap(Ghost.Cap c) {
        SectionMesher.BakedCap b = c.baked;
        c.baked = null;
        c.bakedVersion = c.bakingVersion;
        if (b != null && liveFormat != null && !sameFormat(b.mesh().drawState().format(), liveFormat)) {
            b.close();
            c.state = Ghost.Section.IDLE;
            return;
        }
        if (b == null) {
            if (c.buffer != null) c.buffer.close();
            c.buffer = null;
            c.meshFormat = null;
            c.indexCount = 0;
            c.quads = 0;
            c.state = Ghost.Section.UPLOADED;
            return;
        }
        try {
            GpuBuffer fresh = RenderSystem.getDevice().createBuffer(() -> "Build Buddy ghost cap", GpuBuffer.USAGE_VERTEX, b.mesh().vertexBuffer());
            if (c.buffer != null) c.buffer.close();
            c.buffer = fresh;
            c.indexCount = b.mesh().drawState().indexCount();
            c.meshFormat = b.mesh().drawState().format();
            c.quads = b.quads();
            RenderSystem.getSequentialBuffer(SectionMesher.renderType().primitiveTopology()).requestIndexCount(c.indexCount);
            c.state = Ghost.Section.UPLOADED;
        } catch (RuntimeException e) {
            BuildBuddy.LOG.error("Ghost cap upload failed", e);
            c.state = Ghost.Section.FAILED;
        } finally {
            b.close();
        }
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

    private static final java.util.function.Predicate<BlockState> HAS_WATER = st -> st.getFluidState().is(FluidTags.WATER);

    /**
     * Which blocks of a section stand in water right now, in the mesher's order, or null when none does. Read here, on the
     * render thread, because the world's blocks must not be read from a worker while the game changes them; the cheap
     * palette check of the world's own sections keeps a dry place from paying for a block-by-block look.
     */
    private static byte[] wetCells(Ghost.Section s) {
        ClientLevel level = s.ghost.level;
        if (level == null) return null;
        int x0 = s.wx + s.x, y0 = s.wy + s.y, z0 = s.wz + s.z;
        boolean maybe = false;
        for (int cx = x0 >> 4; cx <= (x0 + s.w - 1) >> 4 && !maybe; cx++) {
            for (int cz = z0 >> 4; cz <= (z0 + s.d - 1) >> 4 && !maybe; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (int sy = y0 >> 4; sy <= (y0 + s.h - 1) >> 4; sy++) {
                    int idx = chunk.getSectionIndex(sy << 4);
                    if (idx < 0 || idx >= chunk.getSectionsCount()) continue;
                    if (chunk.getSection(idx).maybeHas(HAS_WATER)) {
                        maybe = true;
                        break;
                    }
                }
            }
        }
        if (!maybe) return null;
        byte[] wet = new byte[s.w * s.h * s.d];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int n = 0;
        for (int y = 0; y < s.h; y++) {
            for (int z = 0; z < s.d; z++) {
                for (int x = 0; x < s.w; x++) {
                    if (s.region.state(s.x + x, s.y + y, s.z + z).isAir()) continue;
                    if (level.getFluidState(pos.set(x0 + x, y0 + y, z0 + z)).is(FluidTags.WATER)) {
                        wet[(y * s.d + z) * s.w + x] = 1;
                        n++;
                    }
                }
            }
        }
        return n == 0 ? null : wet;
    }

    private static void startBake(Ghost.Section s) {
        s.state = Ghost.Section.BAKING;
        VertexFormat format = SectionMesher.renderType().format();
        // what the world looked like when this bake started; a later change bumps the version and bakes the section again
        Verifier v = s.ghost.verifier;
        byte[] mask = v == null ? null : v.snapshot(s.part, s.x, s.y, s.z, s.w, s.h, s.d);
        byte[] wet = wetCells(s);
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
                SectionMesher.Baked b = SectionMesher.bake(s.region, s.wx, s.wy, s.wz, s.x, s.y, s.z, s.w, s.h, s.d, s.ghost.level, mask, wet, format);
                s.baked = b;
                s.state = Ghost.Section.BAKED;
                if (s.ghost.disposed && b != null) s.release();
            } catch (Throwable t) {
                BuildBuddy.LOG.error("Ghost section bake failed", t);
                s.failures++;
                s.failedNs = System.nanoTime();
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
            s.meshFormat = null;
            s.indexCount = 0;
            s.quads = 0;
            s.layerQuads = null;
            s.wetQuads = null;
            s.state = Ghost.Section.UPLOADED;
            return 0;
        }
        // made for a layout that is no longer the one drawn (a shader pack was switched while it baked): throw it away and bake again
        if (liveFormat != null && !sameFormat(b.mesh().drawState().format(), liveFormat)) {
            b.close();
            s.state = Ghost.Section.IDLE;
            return 0;
        }
        int bytes = b.mesh().vertexBuffer().remaining();
        try {
            GpuBuffer fresh = RenderSystem.getDevice().createBuffer(() -> "Build Buddy ghost section", GpuBuffer.USAGE_VERTEX, b.mesh().vertexBuffer());
            if (s.buffer != null) s.buffer.close();
            s.buffer = fresh;
            s.indexCount = b.mesh().drawState().indexCount();
            s.meshFormat = b.mesh().drawState().format();
            s.quads = b.quads();
            s.layerQuads = b.layerQuads();
            s.wetQuads = b.wetQuads();
            RenderSystem.getSequentialBuffer(SectionMesher.renderType().primitiveTopology()).requestIndexCount(s.indexCount);
            s.state = Ghost.Section.UPLOADED;
            Stats.uploaded++;
            Stats.bytesUploaded.add(bytes);
        } catch (RuntimeException e) {
            BuildBuddy.LOG.error("Ghost section upload failed", e);
            s.failures++;
            s.failedNs = System.nanoTime();
            s.state = Ghost.Section.FAILED;
            return 0;
        } finally {
            b.close();
        }
        return bytes;
    }

    /** Draws the items in a render pass of their own on the main target; false when the pipeline is not (yet) the layout the meshes are in. */
    private static boolean drawOwnPass(RenderTarget target, List<DrawItem> items) {
        if (!pipelineFits()) return false;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "Build Buddy ghost", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            return drawItems(pass, items);
        }
    }

    /** The pipeline must expect the layout the meshes were written in; if it moved on this very frame, wait for the next. */
    private static boolean pipelineFits() {
        RenderPipeline pipeline = SectionMesher.renderType().prepare().pipeline();
        VertexFormat expected = pipeline.getVertexFormatBinding(0);
        return expected == null || liveFormat == null || sameFormat(expected, liveFormat);
    }

    /** Draws the items in an open pass (the default uniforms are bound); false when the layout does not fit. */
    private static boolean drawItems(RenderPass pass, List<DrawItem> items) {
        PreparedRenderType prepared = SectionMesher.renderType().prepare();
        RenderPipeline pipeline = prepared.pipeline();
        VertexFormat expected = pipeline.getVertexFormatBinding(0);
        if (expected != null && liveFormat != null && !sameFormat(expected, liveFormat)) return false;
        var index = RenderSystem.getSequentialBuffer(SectionMesher.renderType().primitiveTopology());
        pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
        for (PreparedRenderType.Texture t : prepared.textures()) pass.setUniform(t.name(), t.textureView(), t.sampler());
        for (DrawItem it : items) {
            pass.setUniform("DynamicTransforms", it.transforms());
            pass.setVertexBuffer(0, it.buffer().slice());
            pass.setIndexBuffer(index.getBuffer(it.indexCount()), index.type());
            pass.drawIndexed((it.q1() - it.q0()) * 6, 1, it.q0() * 6, 0, 0);
        }
        return true;
    }
}
