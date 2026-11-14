package cn.splitplay.cursor;

import cn.splitplay.SplitPlay;
import cn.splitplay.SplitPlayClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * The pointer each player sees inside their own window.
 *
 * <p>Two windows share one system pointer, so the arrow has to be drawn by the game. Its position
 * is kept in window pixels and fed from raw input deltas, which makes it independent of where the
 * single Windows cursor happens to be.</p>
 *
 * <p>Two details matter and both were established by experiment:</p>
 * <ul>
 *   <li>It has to be drawn from inside a screen's render pass. Drawing at the end of {@code
 *       GameRenderer.render} produces no visible pixels at all, even though the code runs.</li>
 *   <li>Depth has to be raised. GUI items sit around z=100..200 and tooltips at z=400 in the ortho
 *       projection of the GUI pass, so an item overlaps the arrow and wins regardless of draw
 *       order. {@link #OVERLAY_Z} puts the arrow in front of both.</li>
 * </ul>
 */
public final class VirtualCursor {

    private static final float OVERLAY_Z = 800.0F;

    private static final int WIDTH = CursorGeometry.WIDTH;
    private static final int HEIGHT = CursorGeometry.HEIGHT;

    /** Classic arrow outline, one entry per row: {left, right} inclusive. */
    private static final int[][] ARROW = CursorGeometry.ARROW;

    private static final int[][] OUTLINE = CursorGeometry.OUTLINE;

    private static double x;
    private static double y;
    private static boolean positioned;

    /** Button held on the virtual cursor; -1 when none. Drives the drag path and the cursor colour. */
    private static int activeButton = -1;

    private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** GLFW handle whose pointer is currently confined, and whether the clip is in place. */
    private static volatile long pointerWindow;
    private static volatile boolean clipped;

    private VirtualCursor() {
    }

    /** Called when a screen opens: start in the middle instead of wherever the pointer was. */
    public static void recentre(Minecraft minecraft) {
        x = minecraft.getWindow().getWidth() / 2.0D;
        y = minecraft.getWindow().getHeight() / 2.0D;
        positioned = true;
    }

    /**
     * Moves the cursor in <em>window pixels</em>; the caller has already applied speed and
     * sensitivity. Pixel space is what vanilla stores in {@code MouseHandler.xpos}, so keeping the
     * cursor here means one conversion instead of two.
     */
    public static void move(Minecraft minecraft, double dx, double dy) {
        if (!positioned) {
            recentre(minecraft);
        }
        int width = minecraft.getWindow().getWidth();
        int height = minecraft.getWindow().getHeight();
        x = clamp(x + dx, 0.0D, Math.max(1, width - 1));
        y = clamp(y + dy, 0.0D, Math.max(1, height - 1));
    }

    /** Cursor position in gui coordinates, which is what screens hit test against. */
    public static double guiX(Minecraft minecraft) {
        var window = minecraft.getWindow();
        return x * window.getGuiScaledWidth() / (double) window.getWidth();
    }

    public static double guiY(Minecraft minecraft) {
        var window = minecraft.getWindow();
        return y * window.getGuiScaledHeight() / (double) window.getHeight();
    }

    /** Button currently held on the virtual cursor, or {@code -1}. */
    public static int activeButton() {
        return activeButton;
    }

    public static void setActiveButton(int button) {
        activeButton = button;
    }

    public static boolean isPositioned() {
        return positioned;
    }

    /** Draws the arrow on top of the screen that is currently rendering. */
    public static void draw(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null || !minecraft.isWindowActive()) {
            return;
        }
        // Exactly the gate the replay uses: without an assignment (or with the assigned devices
        // unplugged) the window keeps vanilla input, so the real pointer is on screen already and a
        // second arrow would only chase it around.
        if (!cn.splitplay.input.Isolation.isActive()) {
            return;
        }
        if (!positioned) {
            recentre(minecraft);
        }

        double scale = minecraft.getWindow().getGuiScale();
        int guiWidth = minecraft.getWindow().getGuiScaledWidth();
        int guiHeight = minecraft.getWindow().getGuiScaledHeight();

        int originX = CursorGeometry.origin(x, scale, WIDTH, guiWidth);
        int originY = CursorGeometry.origin(y, scale, HEIGHT, guiHeight);

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0.0F, 0.0F, OVERLAY_Z);
        try {
            for (int[] offset : OUTLINE) {
                paint(graphics, originX + offset[0], originY + offset[1], 0xFF000000);
            }
            paint(graphics, originX, originY, 0xFFFFFFFF);

            if (LOGGED.add(String.valueOf(minecraft.screen.getClass().getSimpleName()))) {
                cn.splitplay.SplitPlay.LOGGER.info("splitplay cursor: draw at win=({},{}) gui=({},{}) scale={} screen={}",
                        (int) x, (int) y, originX, originY, scale, minecraft.screen.getClass().getSimpleName());
            }
        } finally {
            pose.popPose();
        }
    }

    /** Puts the game's own cursor position on the virtual one, so vanilla hit tests agree. */
    public static void syncVanillaCursor(net.minecraft.client.MouseHandler handler) {
        if (handler instanceof cn.splitplay.mixin.MouseHandlerAccessor accessor) {
            accessor.splitplay$setXpos(x);
            accessor.splitplay$setYpos(y);
        }
    }

    /**
     * Keeps the system pointer in the state this window needs, while isolation is active.
     *
     * <p>The rule is in {@link PointerPolicy}; the mechanics here are the two Win32 calls hiding
     * cannot replace. Hiding the pointer does <em>not</em> stop Windows from moving it with the
     * physical mouse - it only makes the movement invisible - so moving the mouse inside the game
     * also dragged the real pointer around and clicks landed on whatever was behind the window.
     * Clipping confines it to the client area and re-centring it every frame holds it still.</p>
     */
    public static void enforceCursorMode(Minecraft minecraft) {
        long window = minecraft.getWindow().getWindow();
        if (window == 0L) {
            return;
        }
        boolean active = cn.splitplay.input.Isolation.isActive();
        // The OS answer, not GLFW's cached flag: acting on a stale "focused" is what kept the
        // pointer trapped after another application was brought to the front. Both windows belong
        // to the same process, so "our process owns the foreground" is not enough - the pointer has
        // to be confined to *this* window, otherwise the two windows fight over one clip rectangle
        // and the pointer ends up locked to the wrong half of the screen.
        boolean focused = ownsForeground(window);
        PointerPolicy policy = PointerPolicy.decide(active, focused, minecraft.screen != null);

        if (minecraft.mouseHandler instanceof cn.splitplay.mixin.MouseHandlerAccessor accessor
                && minecraft.screen == null) {
            // In the world vanilla gates camera turning and continuous attack on this flag. The
            // unfocused window cannot grab the real cursor, so it has to be set by hand there too.
            accessor.splitplay$setMouseGrabbed(policy.grabbedInWorld());
        }
        if (!active) {
            releasePointer();
            return;
        }

        long glfwWindow = minecraft.getWindow().getWindow();
        if (pointerWindow != glfwWindow) {
            releasePointer();
            pointerWindow = glfwWindow;
        }
        // The pointer is confined with ClipCursor and hidden, never with GLFW_CURSOR_DISABLED.
        // Disabling the cursor makes GLFW capture the mouse, and Windows then stops delivering
        // WM_INPUT to any RIDEV_INPUTSINK window - which looks exactly like "the mouse does not move
        // anywhere, in menus or in the world", while the log keeps counting dropped native events.
        // Clipping gives the same guarantee (the pointer cannot leave this window) without capture.
        boolean confine = focused && (policy.clipsPointer() || policy.grabbedInWorld());
        if (confine) {
            var hwnd = cn.splitplay.launch.NativeWindows.windowHandle(window);
            if (hwnd != null) {
                // Re-asserted every frame: a different process may call ClipCursor(NULL), and the clip
                // is per process, so "we set it once" is not the same as "it is still in force".
                if (!clipped || !cn.splitplay.launch.NativeWindows.clipActive()) {
                    clipped = cn.splitplay.launch.NativeWindows.clipToClient(hwnd);
                    if (clipped) {
                        SplitPlay.LOGGER.info("splitplay: pointer locked to this window");
                    }
                }
                // Clipping lets the physical mouse walk the (invisible) pointer to an edge of the
                // rect, where Windows shows it again for resize borders; holding it at the client
                // centre keeps it off every part of the desktop that could react to a click.
                int[] centre = cn.splitplay.launch.NativeWindows.clientCentre(hwnd);
                if (centre != null) {
                    cn.splitplay.launch.NativeWindows.pinCursor(centre[0], centre[1]);
                }
            }
        } else if (clipped) {
            releaseClip();
        }

        int wanted = policy.hide() ? GLFW.GLFW_CURSOR_HIDDEN : GLFW.GLFW_CURSOR_NORMAL;
        int current = GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR);
        if (current != wanted) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, wanted);
            // Logged on every change: vanilla re-grabs the cursor whenever a screen closes, and that
            // grab is what starves raw input, so the flapping has to be visible in the log.
            SplitPlay.LOGGER.info("splitplay: cursor mode {} -> {} (focused={}, screen={}, clipped={})",
                    describeMode(current), describeMode(wanted), focused,
                    minecraft.screen == null ? "-" : minecraft.screen.getClass().getSimpleName(), clipped);
        }
    }

    /** Whether the given GLFW window is the one Windows currently has in front. */
    private static boolean ownsForeground(long window) {
        var hwnd = cn.splitplay.launch.NativeWindows.windowHandle(window);
        if (hwnd == null) {
            return false;
        }
        var foreground = com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow();
        if (foreground == null || foreground.getPointer() == null) {
            return false;
        }
        return com.sun.jna.Pointer.nativeValue(foreground.getPointer())
                == com.sun.jna.Pointer.nativeValue(hwnd.getPointer());
    }

    /**
     * Hands the pointer back to the desktop.
     *
     * <p>Called when isolation turns off, when the window closes and from the shutdown hook: a clip
     * that outlives the game confines the user's pointer to an area where no window exists and the
     * desktop becomes unusable until they press Escape or log out.</p>
     */
    public static void releasePointer() {
        releaseClip();
        if (pointerWindow != 0L) {
            try {
                if (GLFW.glfwGetInputMode(pointerWindow, GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_HIDDEN) {
                    GLFW.glfwSetInputMode(pointerWindow, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
                }
            } catch (Throwable ignored) {
                // The window may already be gone; the clip is the part that must not leak.
            }
            pointerWindow = 0L;
        }
    }

    private static void releaseClip() {
        if (clipped) {
            cn.splitplay.launch.NativeWindows.releaseClip();
            clipped = false;
        }
    }

    /**
     * Hands the clip back without touching GLFW.
     *
     * <p>Called from the pointer watchdog thread: GLFW must only be used from the thread that owns
     * the window, but {@code ClipCursor(null)} is a plain Win32 call and is the part that has to
     * happen promptly.</p>
     */
    public static void releaseClipOnly() {
        releaseClip();
    }

    private static void paint(GuiGraphics graphics, int originX, int originY, int color) {
        for (int row = 0; row < HEIGHT; row++) {
            int[] span = ARROW[row];
            graphics.fill(originX + span[0], originY + row, originX + span[1] + 1, originY + row + 1, color);
        }
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    private static String describeMode(int mode) {
        if (mode == GLFW.GLFW_CURSOR_DISABLED) {
            return "disabled";
        }
        if (mode == GLFW.GLFW_CURSOR_HIDDEN) {
            return "hidden";
        }
        return mode == GLFW.GLFW_CURSOR_NORMAL ? "normal" : ("mode" + mode);
    }
}
