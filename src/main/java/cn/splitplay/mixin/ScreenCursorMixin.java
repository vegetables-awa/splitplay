package cn.splitplay.mixin;

import cn.splitplay.cursor.VirtualCursor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the pointer for plain screens (title screen, options, chat, ...).
 *
 * <p>Running at the tail of {@code Screen.render} means widgets are already drawn. Container
 * screens keep painting items after calling super, so they need their own hook.</p>
 */
@Mixin(Screen.class)
public abstract class ScreenCursorMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void splitplay$drawPointer(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo callback) {
        VirtualCursor.draw(graphics);
    }
}
