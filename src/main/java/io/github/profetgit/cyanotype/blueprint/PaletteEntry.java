package io.github.profetgit.cyanotype.blueprint;

import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * One palette slot of a region. A block the game does not know (a modded block, a typo) stays in the blueprint as a
 * placeholder: {@code state} is red concrete so it still draws (a barrier would be invisible) and counts, and {@code source} keeps the original entry so
 * writing the blueprint back loses nothing.
 */
public record PaletteEntry(BlockState state, CompoundTag source, boolean unknown) {
    public static final PaletteEntry AIR = new PaletteEntry(Blocks.AIR.defaultBlockState(), NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()), false);

    public boolean isAir() {
        return !unknown && state.isAir();
    }

    /** The block id as written in the file ("minecraft:oak_stairs", or the unknown block's own id). */
    public String name() {
        return nameOf(source);
    }

    public static PaletteEntry of(BlockState state) {
        return new PaletteEntry(state, NbtUtils.writeBlockState(state), false);
    }

    /** Reads either spelling of an entry: the current {id, properties} or the older {Name, Properties}. */
    public static PaletteEntry read(CompoundTag tag) {
        String name = nameOf(tag);
        Identifier id = Identifier.tryParse(name);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return new PaletteEntry(Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState(), tag.copy(), true);
        }
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        BlockState state = block.defaultBlockState();
        Optional<CompoundTag> props = tag.getCompound("properties").or(() -> tag.getCompound("Properties"));
        if (props.isPresent()) {
            StateDefinition<Block, BlockState> def = block.getStateDefinition();
            for (String key : props.get().keySet()) {
                Property<?> property = def.getProperty(key);
                if (property != null) state = withValue(state, property, props.get().getStringOr(key, ""));
            }
        }
        return new PaletteEntry(state, tag.copy(), false);
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    private static String nameOf(CompoundTag tag) {
        return tag.getString("id").orElseGet(() -> tag.getStringOr("Name", ""));
    }

    /** The entry as the current game version writes a block state; unknown blocks keep their original tag. */
    public CompoundTag toTag() {
        return unknown ? source.copy() : NbtUtils.writeBlockState(state);
    }
}
