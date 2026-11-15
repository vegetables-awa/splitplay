package cn.splitplay.mixin;

import cn.splitplay.SplitPlayClient;
import cn.splitplay.client.TitleButtons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the entry point: one button that opens the second window and splits the screen, one that opens the
 * device picker. The geometry lives in {@link TitleButtons} so the tick loop can add the same buttons again
 * when a mod pack rebuilds the title screen.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void splitplay$addButtons(CallbackInfo callback) {
        TitleButtons.ensure(Minecraft.getInstance());
    }
}
