package io.github.profetgit.cyanotype.auto;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Where a player could click to put a block in a place: on any of the six neighbours' faces that look at it, at a few
 * spots of the face (the spot decides top or bottom for slabs, stairs and trapdoors). Pure geometry; the game decides which
 * of them gives the block that is wanted.
 */
public final class Candidates {
    /** The spots tried on a face, as fractions across it: the middle first, then a quarter off in each direction. */
    static final double[][] SPOTS = {{0.5, 0.5}, {0.5, 0.25}, {0.5, 0.75}, {0.25, 0.5}, {0.75, 0.5}};

    /** A click: on {@code face} of the block at {@code support}, {@code u} and {@code v} across it, or at {@code exact} when the spot was worked out on the block's real outline. */
    public record Click(BlockPos support, Direction face, double u, double v, @Nullable Vec3 exact) {
        public Click(BlockPos support, Direction face, double u, double v) {
            this(support, face, u, v, null);
        }

        public Vec3 point() {
            return exact != null ? exact : Candidates.point(support, face, u, v);
        }

        /** The cell a block put by this click lands in (when the clicked block does not give way). */
        public BlockPos lands() {
            return support.relative(face);
        }
    }

    private Candidates() {
    }

    /** All clicks that would place a block at {@code target} against a neighbour. */
    public static List<Click> around(BlockPos target) {
        List<Click> out = new ArrayList<>();
        for (double[] s : SPOTS) {
            for (Direction d : Direction.values()) out.add(new Click(target.relative(d), d.getOpposite(), s[0], s[1]));
        }
        // the middle of every face first, then the off-centre spots
        out.sort((a, b) -> Integer.compare(spot(a), spot(b)));
        return out;
    }

    /** Clicks on the block at {@code target} itself, for putting another one of the same into it (a second slab, one more candle) or replacing tall grass. */
    public static List<Click> onSelf(BlockPos target) {
        List<Click> out = new ArrayList<>();
        for (double[] s : SPOTS) for (Direction d : Direction.values()) out.add(new Click(target, d, s[0], s[1]));
        out.sort((a, b) -> Integer.compare(spot(a), spot(b)));
        return out;
    }

    private static int spot(Click c) {
        for (int i = 0; i < SPOTS.length; i++) if (SPOTS[i][0] == c.u && SPOTS[i][1] == c.v) return i;
        return SPOTS.length;
    }

    /** The point on a face of a block: {@code u} and {@code v} are 0..1 along the face's two other axes (x then z for up and down, z then y for east and west, x then y for north and south). */
    public static Vec3 point(BlockPos b, Direction face, double u, double v) {
        double x = b.getX(), y = b.getY(), z = b.getZ();
        return switch (face.getAxis()) {
            case Y -> new Vec3(x + u, y + (face == Direction.UP ? 1 : 0), z + v);
            case X -> new Vec3(x + (face == Direction.EAST ? 1 : 0), y + v, z + u);
            case Z -> new Vec3(x + u, y + v, z + (face == Direction.SOUTH ? 1 : 0));
        };
    }
}
