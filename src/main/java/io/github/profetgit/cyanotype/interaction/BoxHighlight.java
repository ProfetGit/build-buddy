package io.github.profetgit.cyanotype.interaction;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Lights up the blocks that are inside the Save area box, each by its own shape (a slab is a half block, a torch a thin post, an
 * anvil an anvil), so it is plain what the box holds and what it leaves out. Shown only while a side of the box is being moved.
 * Blocks buried inside solid ones are left out (nothing of them can be seen), and a very big box is not lit at all: the list is
 * made once for a box, not every frame.
 */
final class BoxHighlight {
    /** Boxes with more cells than this are not lit (reading them would stall the game while a side is dragged). */
    static final long MAX_CELLS = 250_000L;
    /** The most shapes drawn. Beyond it every n-th block is drawn, so the whole box still shows. */
    static final int MAX_SHAPES = 7000;
    /** Blocks farther than this from the camera are not lit. */
    private static final double RANGE = 96;
    private static final int COLOR = 0xFF7FE3FF;

    private static final List<AABB> shapes = new ArrayList<>();
    private static @Nullable SelectionBox builtFor;
    private static boolean tooBig;

    private BoxHighlight() {
    }

    static void clear() {
        shapes.clear();
        builtFor = null;
        tooBig = false;
    }

    /** The shapes lit now (for the demo). */
    static List<AABB> shapes() {
        return shapes;
    }

    static boolean tooBig() {
        return tooBig;
    }

    /** Makes the list for a box, when it is not already made for this one. */
    static void refresh(ClientLevel level, SelectionBox box, Vec3 camera) {
        if (box.equals(builtFor)) return;
        builtFor = box;
        shapes.clear();
        tooBig = box.volume() > MAX_CELLS;
        if (tooBig) return;
        List<AABB> all = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), near = new BlockPos.MutableBlockPos();
        for (int y = box.y0(); y <= box.y1(); y++) {
            for (int z = box.z0(); z <= box.z1(); z++) {
                for (int x = box.x0(); x <= box.x1(); x++) {
                    if (camera.distanceToSqr(x + 0.5, y + 0.5, z + 0.5) > RANGE * RANGE) continue;
                    pos.set(x, y, z);
                    BlockState s = level.getBlockState(pos);
                    if (s.isAir() || s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) continue;
                    if (buried(level, s, pos, near)) continue;
                    VoxelShape shape = s.getShape(level, pos);
                    if (shape.isEmpty()) shape = net.minecraft.world.phys.shapes.Shapes.block();
                    for (AABB part : shape.toAabbs()) all.add(part.move(pos).inflate(0.008));
                }
            }
        }
        if (all.size() <= MAX_SHAPES) {
            shapes.addAll(all);
        } else {
            int step = (all.size() + MAX_SHAPES - 1) / MAX_SHAPES;
            for (int i = 0; i < all.size(); i += step) shapes.add(all.get(i));
        }
    }

    /** A full block with a full block on every side cannot be seen: it is not lit. */
    private static boolean buried(ClientLevel level, BlockState s, BlockPos pos, BlockPos.MutableBlockPos near) {
        if (!s.isSolidRender()) return false;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (!level.getBlockState(near.setWithOffset(pos, d)).isSolidRender()) return false;
        }
        return true;
    }

    static void draw() {
        int fill = (0x4A << 24) | (COLOR & 0xFFFFFF);
        for (AABB a : shapes) Gizmos.cuboid(a, GizmoStyle.fill(fill));
    }
}
