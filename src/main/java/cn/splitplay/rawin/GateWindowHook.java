package cn.splitplay.rawin;

import com.sun.jna.CallbackReference;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.Callback;

/**
 * Subclasses the game window's window procedure so raw input addressed to that window reaches us.
 *
 * <p>Why this exists: a message-only window registered with {@code RIDEV_INPUTSINK} stops receiving
 * mouse {@code WM_INPUT} as soon as the game grabs the cursor, while the game window itself keeps
 * receiving everything (its own mouse callbacks prove it). Registering raw input against the game
 * window and reading the messages here removes that dependency on a second window's delivery.</p>
 *
 * <p>Everything is chained: the previous procedure is called for every message, so GLFW's own raw
 * motion handling keeps working and exceptions never escape into the window procedure.</p>
 */
public final class GateWindowHook {

    private static final int GWLP_WNDPROC = -4;
    private static final int WM_INPUT = 0x00FF;
    private static final int WM_INPUT_DEVICE_CHANGE = 0x00FE;

    /** Kept as a field: JNA does not keep callbacks alive on its own. */
    private static Procedure procedure;
    private static Pointer originalProcedure;
    private static WinDef.HWND hookedWindow;
    private static RawMessageSink sink;
    private static String lastFailure = "not attempted";

    /** Why the last {@link #install(long, RawMessageSink)} returned false. */
    public static String lastFailure() {
        return lastFailure;
    }

    private GateWindowHook() {
    }

    private interface Procedure extends Callback {
        WinDef.LRESULT callback(WinDef.HWND hwnd, int message, WinDef.WPARAM wParam, WinDef.LPARAM lParam);
    }

    /** Installs the hook once; later calls are no-ops. Returns true when raw input is being read here. */
    public static synchronized boolean install(long glfwWindow, RawMessageSink target) {
        if (hookedWindow != null) {
            sink = target;
            return true;
        }
        if (glfwWindow == 0L) {
            lastFailure = "no window handle";
            return false;
        }
        try {
            if (!User32.INSTANCE.IsWindow(new WinDef.HWND(new Pointer(glfwWindow)))) {
                lastFailure = "handle " + Long.toHexString(glfwWindow) + " is not a window";
                return false;
            }
            WinDef.HWND hwnd = new WinDef.HWND(new Pointer(glfwWindow));
            procedure = (WinDef.HWND window, int message, WinDef.WPARAM wParam, WinDef.LPARAM lParam) -> {
                try {
                    RawMessageSink current = sink;
                    if (current != null && message == WM_INPUT) {
                        current.onRawInputMessage(new Pointer(lParam.longValue()));
                    } else if (current != null && message == WM_INPUT_DEVICE_CHANGE) {
                        current.onRawDeviceChange((int) wParam.longValue(), lParam.longValue());
                    }
                } catch (Throwable ignored) {
                    // A window procedure must never throw: the game would lose its message loop.
                }
                return User32.INSTANCE.CallWindowProc(originalProcedure, window, message, wParam, lParam);
            };
            Pointer replacement = CallbackReference.getFunctionPointer(procedure);
            originalProcedure = User32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, replacement);
            if (originalProcedure == null) {
                int error = Native.getLastError();
                lastFailure = "SetWindowLongPtr failed, win32 error " + error
                        + " on handle " + Long.toHexString(glfwWindow);
                procedure = null;
                return false;
            }
            hookedWindow = hwnd;
            sink = target;
            return true;
        } catch (Throwable failure) {
            lastFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            procedure = null;
            originalProcedure = null;
            hookedWindow = null;
            return false;
        }
    }

    /** Restores the original procedure; safe to call when nothing is installed. */
    public static synchronized void remove() {
        if (hookedWindow == null || originalProcedure == null) {
            return;
        }
        try {
            User32.INSTANCE.SetWindowLongPtr(hookedWindow, GWLP_WNDPROC, originalProcedure);
        } catch (Throwable ignored) {
            // Shutting down anyway.
        }
        hookedWindow = null;
        originalProcedure = null;
        procedure = null;
        sink = null;
    }
}
