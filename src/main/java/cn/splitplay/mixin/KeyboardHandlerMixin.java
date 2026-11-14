package cn.splitplay.mixin;

import cn.splitplay.input.Isolation;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops keyboard input that arrives through the normal Windows path.
 *
 * <p>Without this the focused window would receive the keystrokes of both keyboards. Text entry
 * needs its own hook: {@code keyPress} only carries key codes, while typing goes through {@code
 * charTyped}.</p>
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void splitplay$blockKeyPress(long window, int key, int scancode, int action, int modifiers,
            CallbackInfo callback) {
        if (Isolation.shouldCancelNative()) {
            Isolation.noteBlocked();
            callback.cancel();
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void splitplay$blockCharTyped(long window, int codePoint, int modifiers, CallbackInfo callback) {
        if (Isolation.shouldCancelNative()) {
            Isolation.noteBlocked();
            callback.cancel();
        }
    }
}
