package cn.splitplay.mixin;

import cn.splitplay.client.DeviceScreen;
import cn.splitplay.launch.SecondInstance;
import cn.splitplay.launch.SplitLauncher;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the entry point: one button that opens the second window and splits the screen. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void splitplay$addSplitButton(CallbackInfo callback) {
        Screen screen = (Screen) (Object) this;
        // The second window gets the device picker but not the button that starts another window.
        if (!SecondInstance.isChild()) {
            Button button = Button.builder(Component.translatable("splitplay.button.two_players"), b -> SplitLauncher.startTwoPlayerMode())
                    .bounds(6, screen.height - 26, 110, 20)
                    .build();
            ((ScreenAccessor) screen).splitplay$addRenderableWidget(button);
        }

        // Without this the config file has to be edited by hand, which nobody should have to do.
        Button devices = Button.builder(Component.translatable("splitplay.button.devices"),
                        b -> net.minecraft.client.Minecraft.getInstance().setScreen(new DeviceScreen(screen)))
                .bounds(120, screen.height - 26, 90, 20)
                .build();
        ((ScreenAccessor) screen).splitplay$addRenderableWidget(devices);
    }
}
