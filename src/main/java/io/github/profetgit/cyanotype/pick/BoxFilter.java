package io.github.profetgit.cyanotype.pick;

import io.github.profetgit.cyanotype.blueprint.Capture;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * What a saved box leaves out, when the player asked it to: the ground, trees and plants, and the other buildings of a
 * Smart Pick. Everything else in the box is saved, so the rule the player has to know is the box. The three switches are
 * the whole of it: no per-object editing.
 *
 * <ul>
 * <li><b>Ground</b>: blocks of the earth (dirt, grass block, stone, sand, ores).</li>
 * <li><b>Trees and plants</b>: wild plants, untouched leaves, and logs that have a tree's leaves around them (a log with
 * none is a beam, and stays). A planted crop or a player's leaf hedge is a build.</li>
 * <li><b>Other buildings</b>: the parts the picker reached and did not pick (a neighbour joined by a fence, say). Only
 * for a box that came from a Smart Pick; cells the picker never saw are kept.</li>
 * </ul>
 */
public final class BoxFilter implements Capture.Mask {
    private final Picker.Field field;
    private final boolean ground, nature;
    private final @Nullable LongOpenHashSet others;

    /** @param others cells of the parts to leave out, or null to keep every part */
    public BoxFilter(Picker.Field field, boolean ground, boolean nature, @Nullable LongOpenHashSet others) {
        this.field = field;
        this.ground = ground;
        this.nature = nature;
        this.others = others;
    }

    /** Whether the filter leaves anything out at all; a box that keeps everything needs no mask. */
    public boolean filters() {
        return !ground || !nature || others != null && !others.isEmpty();
    }

    @Override
    public boolean keep(int x, int y, int z) {
        if (others != null && !others.isEmpty() && others.contains(BlockPos.asLong(x, y, z))) return false;
        if (ground && nature) return true;
        BlockState s = field.state(x, y, z);
        Kind k = BlockKinds.classify(s);
        if (!ground && k == Kind.TERRAIN) return false;
        if (!nature && (k == Kind.VEGETATION || k == Kind.LOG && grewHere(field, x, y, z))) return false;
        return true;
    }

    /** Whether a tree's leaves are around this log: then it is a trunk, not a beam. */
    static boolean grewHere(Picker.Field f, int x, int y, int z) {
        for (int dy = -3; dy <= 4; dy++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dx = -3; dx <= 3; dx++) {
                    int nx = x + dx, nz = z + dz;
                    if (!f.loaded(nx, nz)) continue;
                    if (BlockKinds.isNaturalLeaf(f.state(nx, y + dy, nz))) return true;
                }
            }
        }
        return false;
    }
}
