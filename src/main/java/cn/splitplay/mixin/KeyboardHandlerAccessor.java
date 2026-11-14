package cn.splitplay.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the two vanilla entry points so replayed events take the real code paths. */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccessor {

    @Invoker("keyPress")
    void splitplay$keyPress(long window, int key, int scancode, int action, int modifiers);

    @Invoker("charTyped")
    void splitplay$charTyped(long window, int codePoint, int modifiers);
}
