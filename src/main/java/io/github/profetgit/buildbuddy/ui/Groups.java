package io.github.profetgit.buildbuddy.ui;

import io.github.profetgit.buildbuddy.pick.BlockKinds;
import io.github.profetgit.buildbuddy.pick.Kind;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The "things" of a build in the Save preview that can be taken out with one click: a tree (trunk and leaves), the ground, a body
 * of water, a patch of plants. Everything else (a wall, a roof, a lamp) is just one block. Worked out once for a box from the
 * kind of each block ({@link BlockKinds}); a hover then only looks up a number. Pure apart from {@link #code}.
 */
public final class Groups {
    public enum Type {
        BLOCK("1 block", "this block"), TREE("a tree", "this tree"), GROUND("the ground", "the ground"), WATER("the water", "this water"), PLANTS("plants", "these plants");

        public final String said, pointing;

        Type(String said, String pointing) {
            this.said = said;
            this.pointing = pointing;
        }
    }

    /** What a cell is, for grouping. */
    public static final byte AIR = 0, BUILT = 1, TERRAIN = 2, PLANT = 3, LEAF = 4, LOG = 5, FLUID = 6;
    /** How far from a log a natural leaf makes it a trunk: a house's log beams have none near them. */
    private static final int TRUNK_LEAF_REACH = 3;
    /** Plants count as one patch when they are no more than one empty cell apart sideways and touching up or down. */
    private static final int PLANT_GAP = 2;

    private static final Groups NONE = new Groups(new int[0], new Type[]{Type.BLOCK}, new int[]{1}, 0, 0, 0);

    private final int[] label;
    private final Type[] type;
    private final int[] size;
    private final int ex, ey, ez;

    private Groups(int[] label, Type[] type, int[] size, int ex, int ey, int ez) {
        this.label = label;
        this.type = type;
        this.size = size;
        this.ex = ex;
        this.ey = ey;
        this.ez = ez;
    }

    /** A block state as the grouping sees it. */
    public static byte code(BlockState s) {
        Kind k = BlockKinds.classify(s);
        return switch (k) {
            case AIR -> AIR;
            case TERRAIN -> TERRAIN;
            case VEGETATION -> BlockKinds.isNaturalLeaf(s) ? LEAF : PLANT;
            case LOG -> LOG;
            case FLUID -> FLUID;
            default -> BUILT;
        };
    }

    public static Groups none() {
        return NONE;
    }

    /** Finds the groups of a box: {@code kinds[cell]} is the {@link #code} of each cell, x fastest, then z, then y. */
    public static Groups of(byte[] kinds, int ex, int ey, int ez) {
        int n = ex * ey * ez;
        if (n == 0 || kinds.length != n) return NONE;
        int[] label = new int[n];
        java.util.ArrayList<Type> types = new java.util.ArrayList<>();
        IntArrayList sizes = new IntArrayList();
        types.add(Type.BLOCK);
        sizes.add(1);
        IntArrayList stack = new IntArrayList();
        for (int seed = 0; seed < n; seed++) {
            if (label[seed] != 0) continue;
            Type t = typeOf(kinds, seed, ex, ey, ez);
            if (t == null) continue;
            int id = types.size();
            types.add(t);
            int count = 0;
            label[seed] = id;
            stack.add(seed);
            while (!stack.isEmpty()) {
                int c = stack.removeInt(stack.size() - 1);
                count++;
                int x = c % ex, z = (c / ex) % ez, y = c / (ex * ez);
                int lx = t == Type.PLANTS ? PLANT_GAP : 1, ly = 1;
                boolean wide = t == Type.TREE || t == Type.PLANTS;
                for (int dy = -ly; dy <= ly; dy++) {
                    for (int dz = -lx; dz <= lx; dz++) {
                        for (int dx = -lx; dx <= lx; dx++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            // ground and water join by faces only; a tree by faces, edges and corners; plants across a gap
                            if (!wide && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) continue;
                            int nx = x + dx, ny = y + dy, nz = z + dz;
                            if (nx < 0 || ny < 0 || nz < 0 || nx >= ex || ny >= ey || nz >= ez) continue;
                            int nc = (ny * ez + nz) * ex + nx;
                            if (label[nc] != 0 || typeOf(kinds, nc, ex, ey, ez) != t) continue;
                            label[nc] = id;
                            stack.add(nc);
                        }
                    }
                }
            }
            sizes.add(count);
        }
        return new Groups(label, types.toArray(new Type[0]), sizes.toIntArray(), ex, ey, ez);
    }

    /** The group a cell can belong to by what it is, or null for a block taken on its own (and for air). */
    private static Type typeOf(byte[] kinds, int cell, int ex, int ey, int ez) {
        return switch (kinds[cell]) {
            case TERRAIN -> Type.GROUND;
            case FLUID -> Type.WATER;
            case PLANT -> Type.PLANTS;
            case LEAF -> Type.TREE;
            case LOG -> hasLeafNear(kinds, cell, ex, ey, ez) ? Type.TREE : null;
            default -> null;
        };
    }

    private static boolean hasLeafNear(byte[] kinds, int cell, int ex, int ey, int ez) {
        int x = cell % ex, z = (cell / ex) % ez, y = cell / (ex * ez);
        for (int dy = -TRUNK_LEAF_REACH; dy <= TRUNK_LEAF_REACH; dy++) {
            int ny = y + dy;
            if (ny < 0 || ny >= ey) continue;
            for (int dz = -TRUNK_LEAF_REACH; dz <= TRUNK_LEAF_REACH; dz++) {
                int nz = z + dz;
                if (nz < 0 || nz >= ez) continue;
                for (int dx = -TRUNK_LEAF_REACH; dx <= TRUNK_LEAF_REACH; dx++) {
                    int nx = x + dx;
                    if (nx >= 0 && nx < ex && kinds[(ny * ez + nz) * ex + nx] == LEAF) return true;
                }
            }
        }
        return false;
    }

    /** The group number of a cell: 0 for a block that stands alone. */
    public int labelOf(int cell) {
        return cell >= 0 && cell < label.length ? label[cell] : 0;
    }

    /** The labels of every cell, or null when there are none (to light a whole group up). */
    int[] labels() {
        return label.length == 0 ? null : label;
    }

    public Type typeOf(int cell) {
        return type[labelOf(cell)];
    }

    /** How many blocks go with a cell when it is taken as a whole: 1 for a block that stands alone. */
    public int sizeOf(int cell) {
        return size[labelOf(cell)];
    }

    /** The cells that go with a cell when it is taken as a whole (just itself for a block that stands alone). */
    public int[] cellsOf(int cell) {
        int l = labelOf(cell);
        if (l == 0) return cell >= 0 ? new int[]{cell} : new int[0];
        int[] out = new int[size[l]];
        int k = 0;
        for (int i = 0; i < label.length && k < out.length; i++) if (label[i] == l) out[k++] = i;
        return out;
    }
}
