package io.github.profetgit.buildbuddy.ponder;

import io.github.profetgit.buildbuddy.blueprint.PaletteEntry;
import io.github.profetgit.buildbuddy.blueprint.SchematicReader;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.profetgit.buildbuddy.ui.BlockLook;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
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

    private static final Identifier STEVE = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
    private volatile Object skinFor;
    private volatile BlockLook.Tex skin;

    /** The default player skin through the resource manager (a resource pack that changes it changes the little player too); null without a game. */
    @Override
    public BlockLook.Tex skin() {
        Minecraft mc;
        try {
            mc = Minecraft.getInstance();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        if (mc == null || mc.getResourceManager() == null) return null;
        Object manager = mc.getResourceManager();
        if (skinFor == manager) return skin;
        BlockLook.Tex t = null;
        Optional<Resource> res = mc.getResourceManager().getResource(STEVE);
        if (res.isPresent()) {
            try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
                int w = img.getWidth(), h = img.getHeight();
                int[] px = new int[w * h];
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = img.getPixel(x, y);
                t = new BlockLook.Tex(w, h, px, 0xFF9B7B5B);
            } catch (java.io.IOException | RuntimeException e) {
                t = null;
            }
        }
        skin = t;
        skinFor = manager;
        return t;
    }

    /** Whether the string names a block this game knows (for the lint over the shipped lessons). */
    public static boolean known(String spec) {
        return !PaletteEntry.read(SchematicReader.stateTag(spec)).unknown();
    }
}
