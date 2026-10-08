package io.github.profetgit.cyanotype.mixin;

import io.github.profetgit.cyanotype.interaction.Keys;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the keys to the options before they load, so their bindings are saved and show in Controls. */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Shadow
    @Final
    @Mutable
    public KeyMapping[] keyMappings;

    @Inject(method = "load", at = @At("HEAD"))
    private void cyanotype$keys(CallbackInfo ci) {
        if (Arrays.asList(keyMappings).contains(Keys.MAIN)) return;
        List<KeyMapping> all = new ArrayList<>(Arrays.asList(keyMappings));
        all.add(Keys.MAIN);
        all.add(Keys.TOGGLE);
        all.add(Keys.UNDO);
        all.add(Keys.REDO);
        all.add(Keys.MIRROR);
        all.add(Keys.PASTE);
        all.add(Keys.REMOVE);
        all.add(Keys.PICK);
        all.add(Keys.HELP);
        all.add(Keys.AUTO);
        all.add(Keys.LAYER_UP);
        all.add(Keys.LAYER_DOWN);
        keyMappings = all.toArray(KeyMapping[]::new);
    }
}
