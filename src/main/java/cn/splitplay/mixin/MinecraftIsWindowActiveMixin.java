package cn.splitplay.mixin;

import cn.splitplay.input.Isolation;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps both windows responsive to their own input.
 *
 * <p>A window without OS focus reports itself inactive, which makes vanilla ignore mouse movement
 * and turn the game into a paused/inactive state. Each player owns one window, so both have to keep
 * working while only one of them is focused.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftIsWindowActiveMixin {

    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void splitplay$stayActive(CallbackInfoReturnable<Boolean> callback) {
        if (Isolation.isActive()) {
            callback.setReturnValue(Boolean.TRUE);
        }
    }
}
