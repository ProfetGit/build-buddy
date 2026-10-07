package io.github.profetgit.cyanotype.ponder;

import io.github.profetgit.cyanotype.blueprint.PaletteEntry;
import io.github.profetgit.cyanotype.blueprint.SchematicReader;
import io.github.profetgit.cyanotype.ui.BlockLook;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.state.BlockState;

/** The game's looks for a lesson's palette: a block state string ("minecraft:oak_stairs[facing=east]") becomes the real textures and model, so the player's resource pack shows. */
public final class PonderLooks implements StageRaster.Looks {
    public static final PonderLooks INSTANCE = new PonderLooks();
    private final Map<String, BlockState> states = new ConcurrentHashMap<>();

    private PonderLooks() {
    }

    /** The block state a string names; an unknown block stands in as red concrete, like in a blueprint. */
    public BlockState state(String spec) {
        return states.computeIfAbsent(spec, s -> PaletteEntry.read(SchematicReader.stateTag(s)).state());
    }

    @Override
    public BlockLook.Look of(String spec) {
        return BlockLook.of(state(spec));
    }

    /** Whether the string names a block this game knows (for the lint over the shipped lessons). */
    public static boolean known(String spec) {
        return !PaletteEntry.read(SchematicReader.stateTag(spec)).unknown();
    }
}
