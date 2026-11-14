package cn.splitplay.mixin;

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
        InputRouter.tick((Minecraft) (Object) this);
    }
}
