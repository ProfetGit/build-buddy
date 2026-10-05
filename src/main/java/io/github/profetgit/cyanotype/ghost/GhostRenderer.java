package io.github.profetgit.cyanotype.ghost;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import io.github.profetgit.cyanotype.Cyanotype;
import io.github.profetgit.cyanotype.placement.Placement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Draws the ghosts. Everything here runs on the render thread except the bake jobs. Each frame: notice a changed
 * level or resource reload (rebuild), start bake jobs for the nearest unbaked sections, upload finished meshes
 * (bounded per frame), then draw every visible uploaded section in one render pass, nearest first, with the block
 * pipeline's translucent variant so fog, lightmap and shader packs treat it as terrain.
 */
public final class GhostRenderer {
    private static final List<Ghost> GHOSTS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4)), r -> {
        Thread t = new Thread(r, "Cyanotype baker");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final int MAX_IN_FLIGHT = 8;
    private static final int UPLOAD_BYTES_PER_FRAME = 4 << 20;
    private static Object lastModels;

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

    public static Ghost show(Placement placement) {
        Minecraft mc = Minecraft.getInstance();
        Ghost g = new Ghost(placement, mc.level);
        GHOSTS.add(g);
        pendingInRange = -1;
        prepare(g);
        return g;
    }

    public static void hide(Placement placement) {
        for (Ghost g : GHOSTS) {
            if (g.placement == placement) {
                GHOSTS.remove(g);
                g.dispose();
            }
        }
    }

    public static void clear() {
        for (Ghost g : GHOSTS) g.dispose();
        GHOSTS.clear();
    }

    /** Rebuilds a placement's ghost after its origin, orientation or blueprint changed. */
    public static void rebuild(Placement placement) {
        hide(placement);
        if (placement.visible) show(placement);
    }

    public static List<Ghost> ghosts() {
        return GHOSTS;
    }

    private static void prepare(Ghost g) {
        WORKERS.execute(() -> {
            try {
                g.prepare();
            } catch (Throwable t) {
                Cyanotype.LOG.error("Could not prepare ghost of {}", g.placement.name, t);
            }
        });
    }

    /** Sections in range that still wait to be baked or uploaded; -1 until a frame has looked. */
    private static volatile int pendingInRange = -1;

    /** Whether every section within range of the camera has finished baking and uploading (dev checks). */
    public static boolean settled() {
        return pendingInRange == 0 && !GHOSTS.isEmpty() && GHOSTS.stream().allMatch(g -> g.sections != null);
    }

    /** Called once per frame from the level renderer, after the main pass has composed the scene. */
    public static void render(Minecraft mc, CameraRenderState cam, com.mojang.blaze3d.pipeline.RenderTarget target) {
        if (GHOSTS.isEmpty()) return;
        long t0 = System.nanoTime();
        try {
            renderTimed(mc, cam, target);
        } finally {
            Stats.cpuNanos += System.nanoTime() - t0;
            Stats.frames++;
        }
    }

    private static void renderTimed(Minecraft mc, CameraRenderState cam, com.mojang.blaze3d.pipeline.RenderTarget target) {
        ClientLevel level = mc.level;
        Object models = mc.getModelManager().getBlockStateModelSet();
        boolean reloaded = lastModels != null && models != lastModels;
        lastModels = models;
        for (Ghost g : GHOSTS) {
            boolean otherLevel = g.level != level;
            if (reloaded || otherLevel) {
                GHOSTS.remove(g);
                g.dispose();
                // a resource reload changes the baked UVs: rebuild in place; another world's ghost is dropped
                if (reloaded && !otherLevel && g.placement.visible) show(g.placement);
            }
        }
        if (level == null) return;

        double cx = cam.pos.x, cy = cam.pos.y, cz = cam.pos.z;
        double rangeSq = range * range;
        List<Ghost.Section> candidates = new ArrayList<>();
        int total = 0;
        for (Ghost g : GHOSTS) {
            List<Ghost.Section> list = g.sections;
            if (list == null || !g.placement.visible) continue;
            total += list.size();
            for (Ghost.Section s : list) {
                if (distSq(s, cx, cy, cz) <= rangeSq) candidates.add(s);
            }
        }
        Stats.sections = total;
        int pending = 0;
        for (Ghost.Section s : candidates) {
            if (s.state == Ghost.Section.IDLE || s.state == Ghost.Section.BAKING || s.state == Ghost.Section.BAKED) pending++;
        }
        for (Ghost g : GHOSTS) if (g.sections == null) pending++;
        pendingInRange = pending;
        candidates.sort(Comparator.comparingDouble(s -> distSq(s, cx, cy, cz)));

        for (Ghost.Section s : candidates) {
            if (IN_FLIGHT.get() >= MAX_IN_FLIGHT) break;
            if (s.state == Ghost.Section.IDLE) startBake(s);
        }
        int budget = UPLOAD_BYTES_PER_FRAME;
        for (Ghost.Section s : candidates) {
            if (s.state != Ghost.Section.BAKED) continue;
            if (budget <= 0) break;
            budget -= upload(s);
        }

        List<Ghost.Section> draw = new ArrayList<>();
        for (Ghost.Section s : candidates) {
            if (s.state == Ghost.Section.UPLOADED && cam.cullFrustum.isVisible(s.bounds)) draw.add(s);
        }
        Stats.drawn = draw.size();
        if (draw.isEmpty()) {
            Stats.quadsDrawn = 0;
            return;
        }
        draw(cam, target, draw);
    }

    private static double distSq(Ghost.Section s, double cx, double cy, double cz) {
        double dx = Math.max(Math.max(s.bounds.minX - cx, 0), cx - s.bounds.maxX);
        double dy = Math.max(Math.max(s.bounds.minY - cy, 0), cy - s.bounds.maxY);
        double dz = Math.max(Math.max(s.bounds.minZ - cz, 0), cz - s.bounds.maxZ);
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

    private static void draw(CameraRenderState cam, com.mojang.blaze3d.pipeline.RenderTarget target, List<Ghost.Section> sections) {
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
            for (Ghost.Section s : sections) {
                float opacity = s.ghost.placement.opacity;
                var slice = RenderSystem.getDynamicUniforms().writeTransform(
                    modelView,
                    new Vector4f(tintR, tintG, tintB, opacity),
                    new Vector3f((float) (s.wx + s.x - cam.pos.x), (float) (s.wy + s.y - cam.pos.y), (float) (s.wz + s.z - cam.pos.z)),
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
