package io.github.profetgit.cyanotype.interaction;

import io.github.profetgit.cyanotype.verify.Verifier;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Marks where one kind of material still goes: a click on a row of the Materials screen lights up the cells of the build
 * that are missing or wrong for it, the nearest few hundred (a build can have far more than anything can draw), with a
 * label on the closest. It stays for a while, or until the same row is clicked again.
 */
public final class CellHighlight {
    private static final int MAX_CELLS = 400;
    private static final long LASTS_NS = 40_000_000_000L, REBUILD_NS = 400_000_000L;
    private static final int GOLD = 0xFFC857;

    private static Verifier verifier;
    private static boolean[][] want;
    private static String key = "", label = "";
    private static int layerLo = -1, layerHi = -1;
    private static long untilNs;
    private static long builtNs, builtChanges = -1;
    private static Vec3 builtAt = Vec3.ZERO;
    private static final LongArrayList cells = new LongArrayList();
    private static int total;

    private CellHighlight() {
    }

    /** Lights up the cells of these palette slots ({part, slot} pairs) within a range of layers (negative = open); {@code rowKey} says which row asked, to toggle it off again. */
    public static void show(Verifier v, List<int[]> slots, String rowKey, String text, int layerFrom, int layerTo) {
        boolean[][] w = new boolean[v.parts.size()][];
        for (int i = 0; i < w.length; i++) w[i] = new boolean[v.parts.get(i).region.states.length];
        for (int[] s : slots) w[s[0]][s[1]] = true;
        verifier = v;
        want = w;
        key = rowKey;
        label = text;
        layerLo = layerFrom;
        layerHi = layerTo;
        untilNs = System.nanoTime() + LASTS_NS;
        builtChanges = -1;
        cells.clear();
    }

    public static void clear() {
        verifier = null;
        want = null;
        key = "";
        cells.clear();
    }

    /** Dev demo: how many cells are being drawn. */
    public static int shown() {
        return verifier == null ? 0 : cells.size();
    }

    public static boolean showing(String rowKey) {
        return verifier != null && key.equals(rowKey) && System.nanoTime() < untilNs;
    }

    /** Called once a frame inside the gizmo window (see Interaction.frame). */
    static void emit(Vec3 camera) {
        Verifier v = verifier;
        if (v == null) return;
        long now = System.nanoTime();
        if (now >= untilNs) {
            clear();
            return;
        }
        boolean due = now - builtNs > REBUILD_NS;
        if (builtChanges < 0 || due && (v.changes() != builtChanges || camera.distanceToSqr(builtAt) > 100)) rebuild(v, camera, now);
        if (cells.isEmpty()) return;
        double pulse = 0.5 + 0.5 * Math.sin(now / 1e9 * 4.0);
        double fade = Math.min(1.0, (untilNs - now) / 1.5e9);
        int line = alpha(GOLD, (0.55 + 0.35 * pulse) * fade), fill = alpha(GOLD, (0.10 + 0.10 * pulse) * fade);
        for (int i = 0; i < cells.size(); i++) {
            long c = cells.getLong(i);
            double x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
            Gizmos.cuboid(new AABB(x - 0.01, y - 0.01, z - 0.01, x + 1.01, y + 1.01, z + 1.01), GizmoStyle.strokeAndFill(line, 2.5f, fill)).setAlwaysOnTop();
        }
        long near = cells.getLong(0);
        Vec3 at = new Vec3(BlockPos.getX(near) + 0.5, BlockPos.getY(near) + 1.2, BlockPos.getZ(near) + 0.5);
        double dist = camera.distanceTo(at);
        String text = label + (total > cells.size() ? "   (nearest " + cells.size() + " of " + total + ")" : "   (" + total + ")");
        Handles.label(at.add(0, 0.04 * dist, 0), text, Handles.labelScale(dist, 0.7, 0.045), 0xFFFFFFFF, alpha(GOLD, 1.0));
    }

    private static void rebuild(Verifier v, Vec3 camera, long now) {
        builtNs = now;
        builtChanges = v.changes();
        builtAt = camera;
        cells.clear();
        total = 0;
        record Sec(int part, int sx, int sy, int sz, double dist) {
        }
        List<Sec> secs = new ArrayList<>();
        for (int pi = 0; pi < v.parts.size(); pi++) {
            Verifier.Part p = v.parts.get(pi);
            for (int sy = 0; sy < p.ny; sy++) for (int sz = 0; sz < p.nz; sz++) for (int sx = 0; sx < p.nx; sx++) {
                double dx = p.wx + sx * 16 + 8 - camera.x, dy = p.wy + sy * 16 + 8 - camera.y, dz = p.wz + sz * 16 + 8 - camera.z;
                secs.add(new Sec(pi, sx, sy, sz, dx * dx + dy * dy + dz * dz));
            }
        }
        secs.sort(Comparator.comparingDouble(Sec::dist));
        // everything counts toward the total; only the nearest sections are collected for drawing
        for (Sec s : secs) {
            Verifier.Part p = v.parts.get(s.part());
            boolean[] w = want[s.part()];
            int x1 = Math.min(p.region.sx, s.sx() * 16 + 16), y1 = Math.min(p.region.sy, s.sy() * 16 + 16), z1 = Math.min(p.region.sz, s.sz() * 16 + 16);
            for (int y = s.sy() * 16; y < y1; y++) for (int z = s.sz() * 16; z < z1; z++) {
                int layer = p.region.oy + y;
                if (layerLo >= 0 && layer < layerLo || layerHi >= 0 && layer > layerHi) continue;
                int row = (y * p.region.sz + z) * p.region.sx;
                for (int x = s.sx() * 16; x < x1; x++) {
                    byte st = p.status[row + x];
                    if ((st != Verifier.MISSING && st != Verifier.WRONG) || !w[p.region.blocks[row + x] & 0xFFFF]) continue;
                    total++;
                    if (cells.size() < MAX_CELLS) cells.add(BlockPos.asLong(p.wx + x, p.wy + y, p.wz + z));
                }
            }
        }
        // nearest first within what was collected, so the label sits on the closest
        List<Long> sorted = new ArrayList<>(cells);
        sorted.sort(Comparator.comparingDouble(c -> camera.distanceToSqr(BlockPos.getX(c) + 0.5, BlockPos.getY(c) + 0.5, BlockPos.getZ(c) + 0.5)));
        cells.clear();
        for (long c : sorted) cells.add(c);
    }

    private static int alpha(int rgb, double a) {
        return ((int) Math.max(0, Math.min(255, a * 255)) << 24) | (rgb & 0xFFFFFF);
    }
}
