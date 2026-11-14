package cn.splitplay.client;

import cn.splitplay.SplitPlay;
import cn.splitplay.launch.SplitLauncher;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * The keyboard entry point, for the cases where a title-screen button cannot be seen.
 *
 * <p>Mod packs (FancyMenu and friends) draw their own main menu and do not render the vanilla widget
 * list, so a button added to {@code TitleScreen} exists but is never drawn. A key shortcut works
 * regardless of what the pack does to the screen.</p>
 */
public final class KeyShortcuts {

    /** Starts two-player mode; with Shift it opens the device picker instead. */
    public static final int KEY = GLFW.GLFW_KEY_F7;

    /** Opens device assignment from the pause menu, so both windows can be configured in-game. */
    public static final int MENU_KEY = GLFW.GLFW_KEY_F8;

    private KeyShortcuts() {
    }

    /** Returns true when the key was consumed. */
    public static boolean handle(int key, boolean shift, Minecraft minecraft) {
        if (minecraft == null) {
            return false;
        }
        if (key == KEY) {
            if (shift) {
                splitplay$openDevices(minecraft);
            } else {
                SplitPlay.LOGGER.info("splitplay: F7 pressed, starting two-player mode");
                SplitLauncher.startTwoPlayerMode();
            }
            return true;
        }
        if (key == MENU_KEY) {
            splitplay$openDevices(minecraft);
            return true;
        }
        return false;
    }

    private static void splitplay$openDevices(Minecraft minecraft) {
        SplitPlay.LOGGER.info("splitplay: opening the device picker from the keyboard shortcut (screen {})",
                minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName());
        minecraft.setScreen(new DeviceScreen(minecraft.screen));
    }
}
