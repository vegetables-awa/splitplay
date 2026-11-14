package cn.splitplay.client;

import cn.splitplay.SplitPlay;
import cn.splitplay.launch.SecondInstance;
import cn.splitplay.launch.SplitLauncher;
import cn.splitplay.mixin.ScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Keeps the two buttons on the title screen.
 *
 * <p>Adding them once from {@code TitleScreen.init} is enough on a vanilla game, but mod packs replace the
 * title screen: the widgets are rebuilt or cleared afterwards, and in some packs the screen is decorated by
 * another mod entirely. Re-adding from the client tick whenever one is missing keeps the entry point
 * reachable without depending on that mod's API.</p>
 */
public final class TitleButtons {

    private static final int DEVICES_WIDTH = 90;
    private static final int TWO_PLAYERS_WIDTH = 110;

    private static final Set<Screen> HANDLED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Screen> REPORTED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static Screen lastLogged;

    private TitleButtons() {
    }

    /** Adds whatever is missing; cheap when nothing is missing. */
    public static void ensure(Minecraft minecraft) {
        if (minecraft == null) {
            return;
        }
        if (minecraft.screen != lastLogged) {
            lastLogged = minecraft.screen;
            if (lastLogged != null) {
                SplitPlay.LOGGER.info("splitplay: screen {} ({} widget(s), title={})",
                        lastLogged.getClass().getName(), lastLogged.children().size(),
                        lastLogged instanceof TitleScreen);
            }
        }
        if (!(minecraft.screen instanceof TitleScreen title)) {
            return;
        }
        boolean hasTwoPlayers = hasButton(title, TWO_PLAYERS_WIDTH, 6);
        boolean hasDevices = hasButton(title, DEVICES_WIDTH, 120);
        if (hasTwoPlayers && hasDevices) {
            HANDLED.add(title);
            return;
        }
        if (!REPORTED.add(title)) {
            // Already reported this screen; still try again in case it was rebuilt a second time.
        } else {
            SplitPlay.LOGGER.info("splitplay: title screen {} has {} widget(s), adding the missing button(s)",
                    title.getClass().getSimpleName(), title.children().size());
        }
        ScreenAccessor accessor = (ScreenAccessor) (Object) title;
        if (!hasDevices) {
            accessor.splitplay$addRenderableWidget(Button
                    .builder(Component.translatable("splitplay.button.devices"),
                            button -> minecraft.setScreen(new DeviceScreen(title)))
                    .bounds(120, title.height - 26, DEVICES_WIDTH, 20)
                    .build());
        }
        if (!hasTwoPlayers && !SecondInstance.isChild()) {
            accessor.splitplay$addRenderableWidget(Button
                    .builder(Component.translatable("splitplay.button.two_players"),
                            button -> SplitLauncher.startTwoPlayerMode())
                    .bounds(6, title.height - 26, TWO_PLAYERS_WIDTH, 20)
                    .build());
        }
        HANDLED.add(title);
    }

    private static boolean hasButton(Screen screen, int width, int x) {
        for (var child : screen.children()) {
            if (child instanceof Button button && button.getWidth() == width && button.getX() == x) {
                return true;
            }
        }
        return false;
    }
}
