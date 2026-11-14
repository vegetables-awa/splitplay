package cn.splitplay.mixin;

import cn.splitplay.cursor.VirtualCursor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the pointer for inventory-like screens.
 *
 * <p>{@code AbstractContainerScreen.render} calls super first and only then paints slots, hover
 * highlights, stack sizes and tooltips, so a hook on {@code Screen.render} alone ends up underneath
 * those items. The {@code OVERLAY_Z} used by {@link VirtualCursor} lifts the arrow above them.</p>
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerCursorMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void splitplay$drawPointer(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo callback) {
        VirtualCursor.draw(graphics);
    }
}
