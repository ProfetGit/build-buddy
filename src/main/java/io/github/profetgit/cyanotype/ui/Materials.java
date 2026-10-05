package io.github.profetgit.cyanotype.ui;

import io.github.profetgit.cyanotype.verify.Verifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * The maths behind the Materials screen (PRD 7.5): what the build still needs, in items, set against what is in the
 * inventory. Needed counts the blocks that are missing or wrong, per palette slot, from the verifier; each block becomes
 * the item that places it; the player's items are subtracted. No drawing here.
 */
public final class Materials {
    private Materials() {
    }

    /** How many of an item one block costs. */
    public record Cost(Item item, int each) {
    }

    /**
     * One line of the list.
     *
     * @param key       stable id: the item's id, or {@code group:...} for a family of variants
     * @param icon      the item to draw (the most needed one of a family)
     * @param variants  1 for a single item, else how many different items the family holds
     * @param slots     which palette slots of the verifier's parts this row covers, as {part, slot}
     * @param cells     blocks of the build still to place or fix for this row
     */
    public record Row(String key, String name, Item icon, int needed, int have, int missing, int stackSize, int variants, List<int[]> slots, int cells) {
        public boolean group() {
            return variants > 1;
        }

        /** What is still to get, as the player counts it: "3 stacks + 12", with the plain number after it when that helps. */
        public String missingText() {
            if (missing <= 0) return "";
            String s = stacks(missing, stackSize);
            return stackSize > 1 && missing >= stackSize ? s + " (" + missing + ")" : s;
        }
    }

    /**
     * @param blocks   blocks to place or fix that come from a row
     * @param noItem   blocks to place or fix that have no item that places them (a cauldron full of water): left out
     */
    public record Result(List<Row> rows, int blocks, int noItem) {
    }

    // ---- block to item

    /**
     * The item that places a block, and how many of it: a double slab is two slabs, four candles four candles. Null when
     * no item is needed or none exists: the top half of a door, the head of a bed, a piston's arm and fluids come with the
     * block that is placed, and a few blocks have no item at all.
     */
    public static Cost costOf(BlockState s) {
        if (companion(s)) return null;
        Block b = s.getBlock();
        Item item = b.asItem();
        if (item == Items.AIR) return null;
        int each = 1;
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE) && s.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) each = 2;
        for (String name : new String[]{"candles", "pickles", "eggs", "flower_amount", "segment_amount"}) {
            each = Math.max(each, amount(s, name));
        }
        if (b == Blocks.SNOW) each = Math.max(each, amount(s, "layers"));
        return new Cost(item, each);
    }

    private static int amount(BlockState s, String property) {
        Property<?> p = s.getBlock().getStateDefinition().getProperty(property);
        if (p == null) return 1;
        Object v = s.getValue(p);
        return v instanceof Integer i ? Math.max(1, i) : 1;
    }

    /** A block that is placed together with another one, or is not placed by hand: it never needs an item of its own. */
    public static boolean companion(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof LiquidBlock || b instanceof PistonHeadBlock || b instanceof MovingPistonBlock) return true;
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
        return s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
    }

    // ---- stacks

    /** "12", "1 stack", "3 stacks + 12". Items that do not stack are just counted. */
    public static String stacks(int count, int stackSize) {
        if (stackSize <= 1 || count < stackSize) return String.valueOf(count);
        int s = count / stackSize, r = count % stackSize;
        String st = s + (s == 1 ? " stack" : " stacks");
        return r == 0 ? st : st + " + " + r;
    }

    // ---- families of variants

    private static final String[] COLOURS = {"light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime", "pink", "gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] WOODS = {"dark_oak", "pale_oak", "oak", "spruce", "birch", "jungle", "acacia", "mangrove", "cherry", "bamboo", "crimson", "warped"};

    /** A set of items that differ only by wood or dye. */
    public record Family(String key, String label) {
    }

    /** The family of an item id ("spruce_planks" is "Planks (any wood)"), or null when it has no wood or dye in its name. */
    public static Family familyOf(String path) {
        String lead = "", rest = path;
        if (rest.startsWith("stripped_")) {
            lead = "stripped_";
            rest = rest.substring("stripped_".length());
        }
        for (String c : COLOURS) {
            if (rest.startsWith(c + "_") && rest.length() > c.length() + 1) return family(lead + rest.substring(c.length() + 1), "colour");
        }
        for (String w : WOODS) {
            if (rest.startsWith(w + "_") && rest.length() > w.length() + 1) return family(lead + rest.substring(w.length() + 1), "wood");
        }
        return null;
    }

    private static Family family(String what, String kind) {
        String text = what.replace('_', ' ');
        return new Family(what + "@" + kind, Character.toUpperCase(text.charAt(0)) + text.substring(1) + " (any " + kind + ")");
    }

    // ---- the list

    private static final class Acc {
        final Item item;
        int needed, cells;
        final List<int[]> slots = new ArrayList<>();

        Acc(Item item) {
            this.item = item;
        }
    }

    /** What the list asks the game about an item; a seam so the maths can run before the game has loaded its item data. */
    public interface ItemInfo {
        ItemInfo GAME = new ItemInfo() {
            @Override
            public String name(Item item) {
                return new ItemStack(item).getHoverName().getString();
            }

            @Override
            public int stackSize(Item item) {
                return Math.max(1, new ItemStack(item).getMaxStackSize());
            }
        };

        String name(Item item);

        int stackSize(Item item);
    }

    public static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    /**
     * What still has to be fetched for a placement.
     *
     * @param layerLo lowest layer counted (0 = the base), or negative for no lower limit
     * @param layerHi highest layer counted, or negative for no upper limit
     * @param have    how many of an item the player has
     * @param group   whether to add up wood and dye variants into one line each
     */
    public static Result compute(Verifier v, int layerLo, int layerHi, ToIntFunction<Item> have, boolean group) {
        return compute(v, layerLo, layerHi, have, group, ItemInfo.GAME);
    }

    public static Result compute(Verifier v, int layerLo, int layerHi, ToIntFunction<Item> have, boolean group, ItemInfo info) {
        Map<Item, Acc> acc = new LinkedHashMap<>();
        int blocks = 0, noItem = 0;
        for (int pi = 0; pi < v.parts.size(); pi++) {
            Verifier.Part p = v.parts.get(pi);
            int[] todo = v.todoBySlot(pi, layerLo, layerHi);
            for (int slot = 0; slot < todo.length; slot++) {
                if (todo[slot] == 0) continue;
                BlockState s = p.region.states[slot];
                Cost c = costOf(s);
                if (c == null) {
                    if (!companion(s)) noItem += todo[slot];
                    continue;
                }
                Acc a = acc.computeIfAbsent(c.item(), Acc::new);
                a.needed += c.each() * todo[slot];
                a.cells += todo[slot];
                a.slots.add(new int[]{pi, slot});
                blocks += todo[slot];
            }
        }
        List<Row> rows = new ArrayList<>();
        if (!group) {
            for (Acc a : acc.values()) rows.add(single(a, have, info));
        } else {
            Map<String, List<Acc>> families = new LinkedHashMap<>();
            Map<String, Family> labels = new LinkedHashMap<>();
            for (Acc a : acc.values()) {
                Family f = familyOf(idOf(a.item));
                String key = f == null ? idOf(a.item) : f.key();
                families.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
                if (f != null) labels.put(key, f);
            }
            for (Map.Entry<String, List<Acc>> e : families.entrySet()) {
                List<Acc> list = e.getValue();
                if (list.size() == 1) {
                    rows.add(single(list.get(0), have, info));
                    continue;
                }
                int needed = 0, got = 0, cells = 0;
                List<int[]> slots = new ArrayList<>();
                Acc best = list.get(0);
                int stack = 64;
                for (Acc a : list) {
                    needed += a.needed;
                    got += have.applyAsInt(a.item);
                    cells += a.cells;
                    slots.addAll(a.slots);
                    stack = Math.min(stack, info.stackSize(a.item));
                    if (a.needed > best.needed) best = a;
                }
                rows.add(new Row("group:" + e.getKey(), labels.get(e.getKey()).label(), best.item, needed, got, Math.max(0, needed - got), stack, list.size(), slots, cells));
            }
        }
        rows.sort(Comparator.comparingInt(Row::missing).reversed().thenComparing(Comparator.comparingInt(Row::needed).reversed()).thenComparing(r -> r.name().toLowerCase(Locale.ROOT)));
        return new Result(rows, blocks, noItem);
    }

    private static Row single(Acc a, ToIntFunction<Item> have, ItemInfo info) {
        int got = have.applyAsInt(a.item);
        return new Row(idOf(a.item), info.name(a.item), a.item, a.needed, got, Math.max(0, a.needed - got), info.stackSize(a.item), 1, a.slots, a.cells);
    }

    // ---- shopping list

    /** The list as text, to paste into a chat or a notes file: what is still to get, biggest shortage first. */
    public static String shoppingList(String title, String scope, List<Row> rows) {
        StringBuilder sb = new StringBuilder("Shopping list: ").append(title);
        if (!scope.isEmpty()) sb.append(" (").append(scope).append(")");
        sb.append("\n\n");
        if (rows.isEmpty()) return sb.append("Nothing left to build.\n").toString();
        boolean any = false;
        for (Row r : rows) {
            if (r.missing() <= 0) continue;
            any = true;
            sb.append("- ").append(r.missingText()).append("  ").append(r.name()).append('\n');
        }
        if (!any) sb.append("You have everything you need.\n");
        return sb.toString();
    }
}
