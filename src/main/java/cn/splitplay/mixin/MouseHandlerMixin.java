package cn.splitplay.mixin;

import cn.splitplay.input.Isolation;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops mouse input that arrives through the normal Windows path.
 *
 * <p>Movement is dropped as well, not only the buttons: two mice share a single system cursor, so
 * the focused window would otherwise be turned by the other player's mouse.</p>
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void splitplay$blockPress(long window, int button, int action, int modifiers, CallbackInfo callback) {
        if (Isolation.shouldCancelNative()) {
            Isolation.noteBlocked();
            callback.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void splitplay$blockScroll(long window, double xOffset, double yOffset, CallbackInfo callback) {
        if (Isolation.shouldCancelNative()) {
            Isolation.noteBlocked();
            callback.cancel();
        }
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void splitplay$blockMove(long window, double x, double y, CallbackInfo callback) {
        if (Isolation.shouldCancelNative()) {
            Isolation.noteBlocked();
            callback.cancel();
        }
    }

    /**
     * Vanilla turns the camera once per tick, from {@code handleAccumulatedMovement}. Our queued movement
     * used to be applied from {@code Minecraft.tick}, which runs after this call, so every delta waited an
     * extra tick and the camera felt a tick behind the hand. Handing the movement over right before the
     * method consumes it removes that delay without changing the cadence.
     */
    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void splitplay$flushQueuedMove(CallbackInfo callback) {
        if (!Isolation.isActive()) {
            return;
        }
        if (this instanceof MouseHandlerAccessor accessor) {
            cn.splitplay.input.InputRouter.flushPendingMove(accessor.splitplay$minecraft());
        }
    }

    /**
     * Keeps vanilla from putting the window into {@code GLFW_CURSOR_DISABLED}.
     *
     * <p>That mode makes GLFW take the Win32 mouse capture, and while the window holds the capture
     * Windows stops delivering mouse {@code WM_INPUT} to it - which froze every raw mouse event the
     * moment a screen was closed. The logical "mouse grabbed" flag is what vanilla actually reads for
     * camera dragging and continuous attack, so we keep that and let this module hide the pointer.</p>
     */
    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void splitplay$skipGrab(CallbackInfo callback) {
        if (!Isolation.isActive()) {
            return;
        }
        if (this instanceof MouseHandlerAccessor accessor) {
            accessor.splitplay$setMouseGrabbed(true);
        }
        callback.cancel();
    }
}
