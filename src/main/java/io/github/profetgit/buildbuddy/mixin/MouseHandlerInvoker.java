package io.github.profetgit.buildbuddy.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Demo scenes scroll through the game's own handler; it is private on 26.2. */
@Mixin(MouseHandler.class)
public interface MouseHandlerInvoker {
    @Invoker("onScroll")
    void buildbuddy$onScroll(long handle, double xoffset, double yoffset);
}
