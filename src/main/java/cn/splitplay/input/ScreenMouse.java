package cn.splitplay.input;

import cn.splitplay.SplitPlayClient;
import cn.splitplay.cursor.VirtualCursor;
import cn.splitplay.mixin.MouseHandlerAccessor;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Feeds the virtual cursor into a screen, the way the reference implementation does.
 *
 * <p>Vanilla's mouse handling cannot be reused here: {@code MouseHandler.onPress} hit tests against
 * one shared system pointer position, and its press bookkeeping (button time, click depth) drives
 * drag detection that a synthetic press never fills in. Calling the screen's own listener methods
 * with our own coordinates is both shorter and exact: whatever the screen would have done with a
 * real click at that spot, it does here.</p>
 */
public final class ScreenMouse {

    /** Raw units in one wheel notch, as reported by Windows. */
    private static final double WHEEL_DELTA = 120.0D;

    /** Raw pixels to window pixels per unit of gui scale; the reference implementation's feel. */
    private static final double BASE_CURSOR_SPEED = 0.18D;

    /** Slowdown while the cursor hovers a slot, and how quickly it eases in and out. */
    private static final double HOVER_SPEED = 0.55D;
    private static final double HOVER_BLEND = 0.35D;

    /** Eased cursor speed factor, so hovering an item does not snap the pointer to a crawl. */
    private static double speedMultiplier = 1.0D;

    private ScreenMouse() {
    }

    /**
     * Applies accumulated raw movement to the virtual cursor and tells the screen about it.
     *
     * @param dx raw horizontal delta
     * @param dy raw vertical delta
     */
    public static void move(Minecraft minecraft, double dx, double dy) {
        Screen screen = minecraft.screen;
        if (screen == null || (dx == 0.0D && dy == 0.0D)) {
            return;
        }
        double guiScale = Math.max(minecraft.getWindow().getGuiScale(), 1.0D);
        // Cursor speed is its own multiplier: the camera one would make the pointer crawl as soon as a
        // player slows the look down.
        double sensitivity = SplitPlayClient.config() == null ? 1.0D : SplitPlayClient.config().cursorSensitivity;
        double target = hoveringSlot(screen) ? HOVER_SPEED : 1.0D;
        speedMultiplier += (target - speedMultiplier) * HOVER_BLEND;
        double speed = BASE_CURSOR_SPEED * guiScale * sensitivity * speedMultiplier;

        double previousGuiX = VirtualCursor.guiX(minecraft);
        double previousGuiY = VirtualCursor.guiY(minecraft);
        VirtualCursor.move(minecraft, dx * speed, dy * speed);
        VirtualCursor.syncVanillaCursor(minecraft.mouseHandler);

        double guiX = VirtualCursor.guiX(minecraft);
        double guiY = VirtualCursor.guiY(minecraft);
        if (guiX == previousGuiX && guiY == previousGuiY) {
            return;
        }
        int active = VirtualCursor.activeButton();
        String name = screen.getClass().getCanonicalName();
        Screen.wrapScreenError(() -> screen.mouseMoved(guiX, guiY), "mouseMoved event handler", name);
        if (active != -1) {
            double dragX = guiX - previousGuiX;
            double dragY = guiY - previousGuiY;
            Screen.wrapScreenError(
                    () -> screen.mouseDragged(guiX, guiY, active, dragX, dragY),
                    "mouseDragged event handler", name);
        }
        screen.afterMouseMove();
    }

    /** Press or release at the virtual cursor position. */
    public static void button(Minecraft minecraft, int button, boolean down) {
        Screen screen = minecraft.screen;
        if (screen == null || !(minecraft.mouseHandler instanceof MouseHandlerAccessor accessor)) {
            return;
        }
        double guiX = VirtualCursor.guiX(minecraft);
        double guiY = VirtualCursor.guiY(minecraft);
        String name = screen.getClass().getCanonicalName();
        if (down) {
            VirtualCursor.setActiveButton(button);
            accessor.splitplay$setActiveButton(button);
            accessor.splitplay$setMousePressedTime(Blaze3D.getTime());
            screen.afterMouseAction();
            Screen.wrapScreenError(() -> screen.mouseClicked(guiX, guiY, button),
                    "mouseClicked event handler", name);
        } else {
            Screen.wrapScreenError(() -> screen.mouseReleased(guiX, guiY, button),
                    "mouseReleased event handler", name);
            if (VirtualCursor.activeButton() == button) {
                VirtualCursor.setActiveButton(-1);
                accessor.splitplay$setActiveButton(-1);
            }
        }
    }

    /** Wheel notch, already in raw units. */
    public static void scroll(Minecraft minecraft, double rawOffsetX, double rawOffsetY) {
        Screen screen = minecraft.screen;
        if (screen == null || (rawOffsetX == 0.0D && rawOffsetY == 0.0D)) {
            return;
        }
        double stepY = rawOffsetY / WHEEL_DELTA;
        double stepX = rawOffsetX / WHEEL_DELTA;
        if (stepY == 0.0D && stepX == 0.0D) {
            return;
        }
        // A discrete wheel has no partial notches; vanilla's own option decides which to use.
        boolean discrete = minecraft.options != null && minecraft.options.discreteMouseScroll().get();
        if (discrete) {
            stepY = Math.signum(stepY);
            stepX = Math.signum(stepX);
        }
        double sensitivity = minecraft.options == null ? 1.0D : minecraft.options.mouseWheelSensitivity().get();
        final double amountY = stepY * sensitivity;
        final double amountX = stepX * sensitivity;
        double guiX = VirtualCursor.guiX(minecraft);
        double guiY = VirtualCursor.guiY(minecraft);
        screen.mouseScrolled(guiX, guiY, amountX, amountY);
        screen.afterMouseAction();
    }

    /**
     * Whether the cursor is over an item slot.
     *
     * <p>Every cursor-speed change while hovering makes precise clicking far easier: an inventory is
     * a grid of 18 pixel targets, and the same speed that feels right in the world overshoots them.</p>
     */
    private static boolean hoveringSlot(Screen screen) {
        if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container)) {
            return false;
        }
        try {
            // The container's own hit test is protected; this is the same arithmetic (slot offsets are
            // 16x16 tiles relative to the GUI origin), done against the public menu instead.
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            double guiX = cn.splitplay.cursor.VirtualCursor.guiX(minecraft);
            double guiY = cn.splitplay.cursor.VirtualCursor.guiY(minecraft);
            var accessor = (cn.splitplay.mixin.ContainerScreenAccessor) container;
            int left = accessor.splitplay$leftPos();
            int top = accessor.splitplay$topPos();
            for (var slot : container.getMenu().slots) {
                if (!slot.isActive()) {
                    continue;
                }
                double x = left + slot.x;
                double y = top + slot.y;
                if (guiX >= x && guiX < x + 16 && guiY >= y && guiY < y + 16) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
