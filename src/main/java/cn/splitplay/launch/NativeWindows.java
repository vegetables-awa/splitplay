package cn.splitplay.launch;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The few Win32 window operations the split layout needs.
 *
 * <p>Everything here could be done from Java except one thing: there is no way to get a window
 * handle for a process, so {@code EnumWindows} plus {@code GetWindowThreadProcessId} is the only
 * route. JNA already ships with Minecraft, so this adds no dependency.</p>
 */
public final class NativeWindows {

    /** The two calls jna-platform 5.14 does not expose with these exact signatures. */
    private interface Extra extends Library {
        Extra INSTANCE = Native.load("user32", Extra.class);

        boolean SetProcessDPIAware();

        int GetSystemMetrics(int index);

        boolean IsIconic(HWND hwnd);

        /** Pins the system pointer inside a rectangle; a null argument releases the pin. */
        boolean ClipCursor(WinUser.RECT rect);

        boolean GetClipCursor(WinUser.RECT rect);

        WinDef.HWND GetCapture();

        boolean ClientToScreen(HWND hwnd, WinDef.POINT point);
    }

    private static final int GWL_STYLE = -16;
    private static final int WS_POPUP = 0x80000000;
    private static final int WS_VISIBLE = 0x10000000;
    private static final int WS_CLIPCHILDREN = 0x02000000;
    /** Removes the caption, thick frame, system menu and min/max boxes in one go. */
    private static final int STYLE_MASK = 0x00CF0000;
    private static final int SWP_FRAMECHANGED = 0x0020;
    private static final int SWP_SHOWWINDOW = 0x0040;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SW_HIDE = 0;
    private static final int SW_SHOW = 5;
    private static final int SW_RESTORE = 9;
    private static final int SM_CXSCREEN = 0;
    private static final int SM_CYSCREEN = 1;

    private static boolean dpiAware;

    private NativeWindows() {
    }

    /** Without this, coordinates are scaled on displays with a scale factor above 100%. */
    public static synchronized void makeDpiAware() {
        if (dpiAware) {
            return;
        }
        try {
            Extra.INSTANCE.SetProcessDPIAware();
        } catch (Throwable ignored) {
            // Older Windows or a locked down session: the layout is just a little off.
        }
        dpiAware = true;
    }

    public static int screenWidth() {
        return Extra.INSTANCE.GetSystemMetrics(SM_CXSCREEN);
    }

    public static int screenHeight() {
        return Extra.INSTANCE.GetSystemMetrics(SM_CYSCREEN);
    }

    /**
     * Finds one window per process id: the largest visible window that has a title. A game creates
     * several helper windows, and the framed main window is the big one. The choice itself lives in
     * {@link MainWindowPicker} so it can be checked without a window on screen.
     */
    public static Map<Long, HWND> findMainWindows(Set<Long> processIds) {
        List<MainWindowPicker.Candidate> candidates = new ArrayList<>();
        Map<Long, HWND> byHandle = new HashMap<>();
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
            long id = pid.getValue() & 0xFFFFFFFFL;
            if (!processIds.contains(id)) {
                return true;
            }
            boolean visible = User32.INSTANCE.IsWindowVisible(hwnd);
            String title = titleOf(hwnd).orElse("");
            WinUser.RECT rect = new WinUser.RECT();
            int width = 0;
            int height = 0;
            if (User32.INSTANCE.GetWindowRect(hwnd, rect)) {
                width = rect.right - rect.left;
                height = rect.bottom - rect.top;
            }
            long handle = Pointer.nativeValue(hwnd.getPointer());
            candidates.add(new MainWindowPicker.Candidate(id, handle, title, width, height, visible));
            byHandle.put(handle, hwnd);
            return true;
        }, Pointer.NULL);

        Map<Long, HWND> best = new HashMap<>();
        MainWindowPicker.pick(candidates).forEach((pid, handle) -> {
            HWND hwnd = byHandle.get(handle);
            if (hwnd != null) {
                best.put(pid, hwnd);
            }
        });
        return best;
    }

    /** Turns a framed game window into a borderless rectangle at the given screen position. */
    public static boolean place(HWND hwnd, int x, int y, int width, int height) {
        return place(hwnd, x, y, width, height, true);
    }

    /**
     * Same as {@link #place(HWND, int, int, int, int)} but can skip activation.
     *
     * <p>The layout is re-applied for a while because the game resizes its own window once the
     * resource reload finishes. Activating on every pass would yank the keyboard focus away from
     * whoever already started playing, so the repeated passes must not activate.</p>
     */
    public static boolean place(HWND hwnd, int x, int y, int width, int height, boolean activate) {
        int style = User32.INSTANCE.GetWindowLong(hwnd, GWL_STYLE);
        int wanted = (style & ~STYLE_MASK) | WS_POPUP | WS_VISIBLE | WS_CLIPCHILDREN;
        User32.INSTANCE.SetWindowLong(hwnd, GWL_STYLE, wanted);
        // ShowWindow(SW_RESTORE) also activates, so only un-minimise when it is actually minimized.
        if (activate || Extra.INSTANCE.IsIconic(hwnd)) {
            User32.INSTANCE.ShowWindow(hwnd, SW_RESTORE);
        }
        int flags = SWP_FRAMECHANGED | SWP_SHOWWINDOW;
        if (!activate) {
            flags |= SWP_NOACTIVATE;
        }
        return User32.INSTANCE.SetWindowPos(hwnd, null, x, y, width, height, flags);
    }

    /** Window rectangle as {@code {x, y, width, height}}, or null when the handle is gone. */
    public static int[] rectOf(HWND hwnd) {
        WinUser.RECT rect = new WinUser.RECT();
        if (!User32.INSTANCE.GetWindowRect(hwnd, rect)) {
            return null;
        }
        return new int[] {rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top};
    }

    public static void hideTaskbar() {
        for (HWND bar : taskbars()) {
            User32.INSTANCE.ShowWindow(bar, SW_HIDE);
        }
    }

    public static void showTaskbar() {
        for (HWND bar : taskbars()) {
            User32.INSTANCE.ShowWindow(bar, SW_SHOW);
        }
    }

    /** True when the taskbar is on screen; false means somebody hid it and forgot to undo it. */
    public static boolean isTaskbarVisible() {
        List<HWND> bars = taskbars();
        if (bars.isEmpty()) {
            return true;
        }
        for (HWND bar : bars) {
            if (User32.INSTANCE.IsWindowVisible(bar)) {
                return true;
            }
        }
        return false;
    }

    /** Primary and secondary taskbars; a multi monitor setup has more than one. */
    private static List<HWND> taskbars() {
        List<HWND> found = new ArrayList<>(2);
        for (String className : new String[] {"Shell_TrayWnd", "Shell_SecondaryTrayWnd"}) {
            HWND hwnd = User32.INSTANCE.FindWindow(className, null);
            if (hwnd != null) {
                found.add(hwnd);
            }
        }
        return found;
    }

    /** Process id of a window, used to tell the two game windows apart. */
    public static long processIdOf(HWND hwnd) {
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
        return pid.getValue() & 0xFFFFFFFFL;
    }

    /**
     * Process id of the window the user is currently working in, or -1.
     *
     * <p>Used to tell "somebody is playing in one of the two game windows" from "the user has gone
     * off to another application", which is the moment the games must stop reacting to the mice.</p>
     */
    public static long foregroundProcessId() {
        HWND hwnd = User32.INSTANCE.GetForegroundWindow();
        return hwnd == null ? -1L : processIdOf(hwnd);
    }

    /**
     * Whether this very process owns the foreground window.
     *
     * <p>This, not GLFW's focus flag, is the question that matters before touching the shared
     * pointer: GLFW's flag is updated from window messages and can lag, and acting on a stale "yes"
     * is exactly how the pointer ended up glued to the game while another application was in use.</p>
     */
    public static boolean foregroundIsOurProcess() {
        return foregroundProcessId() == ProcessHandle.current().pid();
    }

    /** Pointer position in screen coordinates as {@code {x, y}}, or null when unavailable. */
    public static int[] cursorPosition() {
        WinDef.POINT point = new WinDef.POINT();
        return User32.INSTANCE.GetCursorPos(point) ? new int[] {point.x, point.y} : null;
    }

    /** Handle of our own GLFW window, so the pointer trap can be aimed at it. */
    public static HWND windowHandle(long glfwWindow) {
        try {
            long handle = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
            return handle == 0L ? null : new HWND(new Pointer(handle));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Centre of the client area in screen coordinates as {@code {x, y}}, or null. */
    public static int[] clientCentre(HWND hwnd) {
        int[] rect = clientRectOnScreen(hwnd);
        return rect == null ? null : new int[] {rect[0] + rect[2] / 2, rect[1] + rect[3] / 2};
    }

    /** Client area as {@code {x, y, width, height}} in screen coordinates, or null. */
    public static int[] clientRectOnScreen(HWND hwnd) {
        WinUser.RECT rect = new WinUser.RECT();
        if (!User32.INSTANCE.GetClientRect(hwnd, rect)) {
            return null;
        }
        WinDef.POINT origin = new WinDef.POINT(0, 0);
        if (!Extra.INSTANCE.ClientToScreen(hwnd, origin)) {
            return null;
        }
        return new int[] {origin.x, origin.y, rect.right - rect.left, rect.bottom - rect.top};
    }

    /**
     * Confines the system pointer to one window's client area.
     *
     * <p>Hiding the pointer is not enough: Windows keeps moving the hidden pointer with the physical
     * mouse, so a click that lands outside the game hits whatever window is under it. Clipping makes
     * that impossible, and pinning (see {@link #pinCursor}) keeps the pointer from drifting at all.</p>
     */
    public static boolean clipToClient(HWND hwnd) {
        int[] rect = clientRectOnScreen(hwnd);
        if (rect == null) {
            return false;
        }
        WinUser.RECT clip = new WinUser.RECT();
        clip.left = rect[0];
        clip.top = rect[1];
        clip.right = rect[0] + rect[2];
        clip.bottom = rect[1] + rect[3];
        return Extra.INSTANCE.ClipCursor(clip);
    }

    /** Undoes {@link #clipToClient()}. Must be called on the way out, or the desktop stays pinned. */
    public static void releaseClip() {
        try {
            Extra.INSTANCE.ClipCursor(null);
        } catch (Throwable ignored) {
            // Nothing to do: the clip is tied to the process and disappears with it.
        }
    }

    /** Moves the system pointer; used every frame to hold it still against the physical mouse. */
    public static void pinCursor(int x, int y) {
        User32.INSTANCE.SetCursorPos(x, y);
    }

    /** True while any window holds the mouse capture; capture is what starves raw input delivery. */
    public static String captureOwner() {
        try {
            WinDef.HWND capture = Extra.INSTANCE.GetCapture();
            if (capture == null || capture.getPointer() == null) {
                return "none";
            }
            long handle = Pointer.nativeValue(capture.getPointer());
            if (handle == 0L) {
                return "none";
            }
            HWND foreground = User32.INSTANCE.GetForegroundWindow();
            long foregroundHandle = foreground == null || foreground.getPointer() == null
                    ? 0L : Pointer.nativeValue(foreground.getPointer());
            return handle == foregroundHandle ? "same-window" : "other";
        } catch (Throwable failure) {
            return "?";
        }
    }

    /** True when the system pointer is confined to some window right now. */
    public static boolean clipActive() {
        try {
            WinUser.RECT rect = new WinUser.RECT();
            if (!Extra.INSTANCE.GetClipCursor(rect)) {
                return false;
            }
            int width = rect.right - rect.left;
            int height = rect.bottom - rect.top;
            return width > 0 && height > 0 && width < 100000 && height < 100000;
        } catch (Throwable failure) {
            return false;
        }
    }

    public static Optional<String> titleOf(HWND hwnd) {
        int length = User32.INSTANCE.GetWindowTextLength(hwnd);
        if (length <= 0) {
            return Optional.empty();
        }
        char[] buffer = new char[length + 1];
        if (User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new String(buffer).trim());
    }
}
