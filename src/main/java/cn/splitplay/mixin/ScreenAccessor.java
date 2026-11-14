package cn.splitplay.mixin;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code addRenderableWidget} is protected, and this is needed to add a button from a mixin. */
@Mixin(Screen.class)
public interface ScreenAccessor {

    @Invoker("addRenderableWidget")
    GuiEventListener splitplay$addRenderableWidget(GuiEventListener widget);
}
