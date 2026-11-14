package cn.splitplay.mixin;

import cn.splitplay.cursor.VirtualCursor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the system pointer mode in sync with what the player is doing.
 *
 * <p>Drawing used to happen here too, at the end of the frame, but a fresh {@code GuiGraphics}
 * created at this point produces no visible pixels; the arrow is drawn from the screen render pass
 * instead (see {@code ScreenCursorMixin}).</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void splitplay$updateCursorMode(DeltaTracker deltaTracker, boolean renderWorld, CallbackInfo callback) {
        VirtualCursor.enforceCursorMode(Minecraft.getInstance());
    }

    /**
     * Hands queued mouse movement to the virtual cursor before the frame is drawn, so the cursor
     * follows the mouse at frame rate instead of stepping at the 20 Hz tick rate.
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void splitplay$pumpMenuMoves(DeltaTracker deltaTracker, boolean renderWorld, CallbackInfo callback) {
        cn.splitplay.input.InputRouter.pumpFrame(Minecraft.getInstance());
    }
}
