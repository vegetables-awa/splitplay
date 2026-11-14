package cn.splitplay.mixin;

import cn.splitplay.input.InputRouter;
import cn.splitplay.input.Isolation;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes {@code InputConstants.isKeyDown} agree with the keys we replayed.
 *
 * <p>Replaying a key through {@code KeyboardHandler.keyPress} only tells vanilla that the key was
 * pressed; GLFW's own key state stays untouched, because the event never went through the window
 * message queue of this process. Vanilla reads that physical state in a few places, most visibly for
 * debug combos: {@code keyPress} asks {@code isKeyDown(window, GLFW_KEY_F3)} and only then treats the
 * next key as an F3 shortcut. Without this bridge F3 alone toggles the debug screen but F3+H, F3+G,
 * F3+B and the rest do nothing in a split window.</p>
 *
 * <p>Only keys this window actually replayed are reported as down, and only while isolation is on,
 * so the answer never contradicts vanilla's own view of the keyboard when the mod is idle.</p>
 */
@Mixin(InputConstants.class)
public class InputConstantsMixin {

    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void splitplay$heldKeysAreDown(long window, int key, CallbackInfoReturnable<Boolean> callback) {
        if (Isolation.isActive() && InputRouter.isHeld(key)) {
            callback.setReturnValue(true);
        }
    }
}
