package cn.splitplay.mixin;

import cn.splitplay.SplitPlayClient;
import cn.splitplay.client.TitleButtons;
import cn.splitplay.input.InputRouter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies queued raw input on the client thread.
 *
 * <p>Raw input arrives on its own thread; vanilla input handling is not thread safe, so events are
 * only replayed from here.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftTickMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void splitplay$applyRawInput(CallbackInfo callback) {
        // Cheap after the first successful call, but it is the only place the game window handle is
        // guaranteed to exist.
        SplitPlayClient.ensureWindowAttached();
        // Mod packs rebuild or decorate the title screen, so the buttons are re-added when missing.
        TitleButtons.ensure((Minecraft) (Object) this);
        InputRouter.tick((Minecraft) (Object) this);
    }
}
