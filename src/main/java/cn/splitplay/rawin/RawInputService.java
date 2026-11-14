package cn.splitplay.rawin;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.Callback;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global raw-input collector built on a message-only window.
 *
 * <p>Replaces the external {@code splitter.exe} of the original mod: every game process creates its
 * own hidden window, registers keyboards and mice with {@code RIDEV_INPUTSINK} (so events arrive even
 * when the window has no focus), decodes {@code WM_INPUT} into {@link RawInputEvent}s tagged with the
 * device interface path, and hands them to listeners. Because the path is process independent, each
 * instance can simply drop events that belong to the other player's devices - no IPC, no fixed port,
 * no single point of failure.</p>
 *
 * <p>Registered with {@code RIDEV_INPUTSINK} only: the legacy input path is left untouched, so normal
 * window handling keeps working and we can still choose to block it ourselves.</p>
 */
public final class RawInputService implements AutoCloseable, RawMessageSink {

    private static final int WM_INPUT = 0x00FF;
    private static final int WM_INPUT_DEVICE_CHANGE = 0x00FE;
    private static final int WM_CLOSE = 0x0010;
    private static final int WM_DESTROY = 0x0002;
    private static final int WM_APP = 0x8000;
    private static final int WM_STOP_SERVICE = 0x8000 + 0x51;

    private static final int GIDC_ARRIVAL = 1;
    private static final int GIDC_REMOVAL = 2;

    // RAWINPUT header: dwType(4) + dwSize(4) + hDevice(ptr) + wParam(ptr)
    private static final int HEADER_SIZE = 8 + 2 * Native.POINTER_SIZE;
    private static final int KEYBOARD_DATA_SIZE = 16; // usMakeCode usFlags usReserved usVKey msg extra
    private static final int MOUSE_DATA_SIZE = 24;

    // RAWKEYBOARD field offsets, relative to the payload
    private static final int KBD_MAKECODE = 0;
    private static final int KBD_FLAGS = 2;
    private static final int KBD_VKEY = 6;
    private static final int RI_KEY_BREAK = 0x01;

    // RAWMOUSE field offsets, relative to the payload (4-byte alignment puts the union at +4)
    private static final int MOU_BUTTON_FLAGS = 4;
    private static final int MOU_BUTTON_DATA = 6;
    private static final int MOU_LAST_X = 12;
    private static final int MOU_LAST_Y = 16;

    private static final int RI_MOUSE_LEFT_BUTTON_DOWN = 0x0001;
    private static final int RI_MOUSE_LEFT_BUTTON_UP = 0x0002;
    private static final int RI_MOUSE_RIGHT_BUTTON_DOWN = 0x0004;
    private static final int RI_MOUSE_RIGHT_BUTTON_UP = 0x0008;
    private static final int RI_MOUSE_MIDDLE_BUTTON_DOWN = 0x0010;
    private static final int RI_MOUSE_MIDDLE_BUTTON_UP = 0x0020;
    private static final int RI_MOUSE_BUTTON_4_DOWN = 0x0040;
    private static final int RI_MOUSE_BUTTON_4_UP = 0x0080;
    private static final int RI_MOUSE_BUTTON_5_DOWN = 0x0100;
    private static final int RI_MOUSE_BUTTON_5_UP = 0x0200;
    private static final int RI_MOUSE_WHEEL = 0x0400;
    private static final int RI_MOUSE_HWHEEL = 0x0800;

    private static final String WINDOW_CLASS = "SplitplayRawInputSink";

    /** WM_INPUT messages seen by the sink window; separates "not delivered" from "filtered out". */
    private static final java.util.concurrent.atomic.AtomicLong MESSAGES =
            new java.util.concurrent.atomic.AtomicLong();
    /** Events handed to listeners, including those the game later ignores. */
    private static final java.util.concurrent.atomic.AtomicLong EMITTED =
            new java.util.concurrent.atomic.AtomicLong();

    private final List<RawInputListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Long, String> pathCache = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    /** Keeps the WndProc strongly reachable: JNA does not track callback lifetimes for us. */
    private final SinkProc proc = new SinkProc();

    private Thread thread;
    private volatile WinDef.HWND window;
    private volatile String lastFailure;
    /** Set when raw input is read from the game window; null for the message-only sink. */
    private WinDef.HWND attached;

    public void addListener(RawInputListener listener) {
        listeners.add(listener);
    }

    public void removeListener(RawInputListener listener) {
        listeners.remove(listener);
    }

    public boolean isRunning() {
        return running.get() && window != null;
    }

    /** Non-null when the last {@link #start()} failed; useful for a GUI error line. */
    public String lastFailure() {
        return lastFailure;
    }

    /**
     * Reads raw input from the game's own window instead of a message-only one.
     *
     * <p>A message-only sink stops receiving mouse {@code WM_INPUT} as soon as the game grabs the
     * cursor, while the game window keeps receiving everything; this is the path that keeps working.</p>
     *
     * @return true when the hook and the device registration both succeeded
     */
    public synchronized boolean attachToWindow(long glfwWindow) {
        lastFailure = null;
        if (GateWindowHook.install(glfwWindow, this)) {
            WinDef.HWND hwnd = new WinDef.HWND(new Pointer(glfwWindow));
            if (registerDevices(hwnd)) {
                attached = hwnd;
                running.set(true);
                return true;
            }
            lastFailure = "RegisterRawInputDevices failed with " + Native.getLastError();
            GateWindowHook.remove();
            return false;
        }
        lastFailure = "could not hook the game window procedure";
        return false;
    }

    /** True when raw input is read from the game window rather than a message-only sink. */
    public boolean attachedToGameWindow() {
        return attached != null;
    }

    @Override
    public void onRawInputMessage(Pointer lParam) {
        MESSAGES.incrementAndGet();
        handleRawInput(lParam);
    }

    @Override
    public void onRawDeviceChange(int wParam, long lParam) {
        if (wParam == GIDC_REMOVAL) {
            pathCache.remove(lParam);
        }
        for (RawInputListener l : listeners) {
            try {
                l.onDevicesChanged();
            } catch (RuntimeException ignored) {
                // a listener must never break the input thread
            }
        }
    }

    public synchronized void start() {
        if (running.get()) {
            return;
        }
        lastFailure = null;
        running.set(true);
        thread = new Thread(this::runLoop, "splitplay-rawinput");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        if (attached != null) {
            GateWindowHook.remove();
            attached = null;
        }
        running.set(false);
        WinDef.HWND w = window;
        if (w != null) {
            // Wake the blocked GetMessage so the thread can tear down and exit.
            User32.INSTANCE.PostMessage(w, WM_STOP_SERVICE, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
        }
        Thread t = thread;
        if (t != null) {
            try {
                t.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        window = null;
    }

    // ------------------------------------------------------------------ internals

    private void runLoop() {
        WinDef.HINSTANCE instance = new WinDef.HINSTANCE();
        instance.setPointer(Kernel32.INSTANCE.GetModuleHandle(null).getPointer());
        WinUser.WNDCLASSEX wc = new WinUser.WNDCLASSEX();
        wc.cbSize = wc.size();
        wc.lpfnWndProc = proc;
        wc.hInstance = instance;
        wc.lpszClassName = WINDOW_CLASS;
        try {
            if (User32.INSTANCE.RegisterClassEx(wc) == null) {
                fail("RegisterClassEx failed, error=" + Native.getLastError());
                return;
            }
            // HWND_MESSAGE is (HWND)-3. It has to be a real 64-bit -3: Pointer.createConstant()
            // truncates it to 0xFFFFFFFD and CreateWindowEx then fails with ERROR_INVALID_WINDOW_HANDLE.
            WinDef.HWND messageParent = new WinDef.HWND(new Pointer(-3L));
            WinDef.HWND hwnd = User32.INSTANCE.CreateWindowEx(
                    0, WINDOW_CLASS, "splitplay-rawinput", 0, 0, 0, 0, 0,
                    messageParent, null, instance, null);
            if (hwnd == null) {
                fail("CreateWindowEx(HWND_MESSAGE) failed, error=" + Native.getLastError());
                return;
            }
            if (!registerDevices(hwnd)) {
                fail("RegisterRawInputDevices failed, error=" + Native.getLastError());
                User32.INSTANCE.DestroyWindow(hwnd);
                return;
            }
            window = hwnd;
            WinUser.MSG msg = new WinUser.MSG();
            while (running.get()) {
                int r = User32.INSTANCE.GetMessage(msg, null, 0, 0);
                if (r <= 0) {
                    break;
                }
                User32.INSTANCE.TranslateMessage(msg);
                User32.INSTANCE.DispatchMessage(msg);
            }
        } finally {
            WinDef.HWND hwnd = window;
            if (hwnd != null) {
                User32.INSTANCE.DestroyWindow(hwnd);
            }
            window = null;
            running.set(false);
        }
    }

    private boolean registerDevices(WinDef.HWND hwnd) {
        // JNA requires structure arrays to share one contiguous allocation, so build them with toArray().
        RawInputApi.RawInputDeviceRegistration[] regs =
                (RawInputApi.RawInputDeviceRegistration[])
                        new RawInputApi.RawInputDeviceRegistration().toArray(2);
        fill(regs[0], RawInputApi.HID_USAGE_GENERIC_KEYBOARD, hwnd);
        fill(regs[1], RawInputApi.HID_USAGE_GENERIC_MOUSE, hwnd);
        for (RawInputApi.RawInputDeviceRegistration r : regs) {
            r.write();
        }
        return RawInputApi.INSTANCE.RegisterRawInputDevices(regs, regs.length, regs[0].size());
    }

    private static void fill(RawInputApi.RawInputDeviceRegistration r, int usage, WinDef.HWND hwnd) {
        r.usUsagePage = RawInputApi.HID_USAGE_PAGE_GENERIC;
        r.usUsage = (short) usage;
        r.dwFlags = RawInputApi.RIDEV_INPUTSINK | RawInputApi.RIDEV_DEVNOTIFY;
        r.hwndTarget = hwnd;
    }

    private void fail(String message) {
        lastFailure = message;
        running.set(false);
    }

    private WinDef.LRESULT onMessage(WinDef.HWND hwnd, int msg, WinDef.WPARAM wParam, WinDef.LPARAM lParam) {
        switch (msg) {
            case WM_INPUT:
                MESSAGES.incrementAndGet();
                handleRawInput(new Pointer(lParam.longValue()));
                return new WinDef.LRESULT(0);
            case WM_INPUT_DEVICE_CHANGE:
                if (wParam.longValue() == GIDC_REMOVAL) {
                    pathCache.remove(lParam.longValue());
                }
                for (RawInputListener l : listeners) {
                    try {
                        l.onDevicesChanged();
                    } catch (RuntimeException ignored) {
                        // a listener must never break the input thread
                    }
                }
                return new WinDef.LRESULT(0);
            case WM_STOP_SERVICE:
                running.set(false);
                User32.INSTANCE.PostQuitMessage(0);
                return new WinDef.LRESULT(0);
            case WM_CLOSE:
                User32.INSTANCE.DestroyWindow(hwnd);
                return new WinDef.LRESULT(0);
            case WM_DESTROY:
                User32.INSTANCE.PostQuitMessage(0);
                return new WinDef.LRESULT(0);
            default:
                return User32.INSTANCE.DefWindowProc(hwnd, msg, wParam, lParam);
        }
    }

    private void handleRawInput(Pointer hRawInput) {
        if (hRawInput == null) {
            return;
        }
        IntByReference size = new IntByReference(0);
        int headerSize = HEADER_SIZE;
        if (RawInputApi.INSTANCE.GetRawInputData(hRawInput, RawInputApi.RID_INPUT, null, size, headerSize) != 0) {
            return;
        }
        int bytes = size.getValue();
        if (bytes < headerSize + 4) {
            return;
        }
        Memory buf = new Memory(bytes);
        size.setValue(bytes);
        if (RawInputApi.INSTANCE.GetRawInputData(hRawInput, RawInputApi.RID_INPUT, buf, size, headerSize) != bytes) {
            return;
        }
        int type = buf.getInt(0);
        int payload = headerSize;
        long deviceHandle = buf.getLong(8);
        String path = pathFor(deviceHandle);
        if (path == null) {
            return;
        }
        if (type == RawInputApi.RIM_TYPEKEYBOARD && bytes >= payload + KEYBOARD_DATA_SIZE) {
            int makeCode = buf.getShort(payload + KBD_MAKECODE) & 0xFFFF;
            int flags = buf.getShort(payload + KBD_FLAGS) & 0xFFFF;
            int vkey = buf.getShort(payload + KBD_VKEY) & 0xFFFF;
            if (vkey != 0 && vkey != 0xFF) {
                dispatch(RawInputEvent.key(path, vkey, makeCode, (flags & RI_KEY_BREAK) == 0));
            }
        } else if (type == RawInputApi.RIM_TYPEMOUSE && bytes >= payload + MOUSE_DATA_SIZE) {
            int buttonFlags = buf.getShort(payload + MOU_BUTTON_FLAGS) & 0xFFFF;
            int buttonData = buf.getShort(payload + MOU_BUTTON_DATA);
            int dx = buf.getInt(payload + MOU_LAST_X);
            int dy = buf.getInt(payload + MOU_LAST_Y);
            if (dx != 0 || dy != 0) {
                dispatch(RawInputEvent.move(path, dx, dy));
            }
            if (buttonFlags != 0) {
                emitButtons(path, buttonFlags);
                if ((buttonFlags & RI_MOUSE_WHEEL) != 0) {
                    dispatch(RawInputEvent.wheel(path, 0, buttonData));
                }
                if ((buttonFlags & RI_MOUSE_HWHEEL) != 0) {
                    dispatch(RawInputEvent.wheel(path, buttonData, 0));
                }
            }
        }
    }

    private void emitButtons(String path, int flags) {
        emit(path, flags, RI_MOUSE_LEFT_BUTTON_DOWN, RI_MOUSE_LEFT_BUTTON_UP, 0);
        emit(path, flags, RI_MOUSE_RIGHT_BUTTON_DOWN, RI_MOUSE_RIGHT_BUTTON_UP, 1);
        emit(path, flags, RI_MOUSE_MIDDLE_BUTTON_DOWN, RI_MOUSE_MIDDLE_BUTTON_UP, 2);
        emit(path, flags, RI_MOUSE_BUTTON_4_DOWN, RI_MOUSE_BUTTON_4_UP, 3);
        emit(path, flags, RI_MOUSE_BUTTON_5_DOWN, RI_MOUSE_BUTTON_5_UP, 4);
    }

    private void emit(String path, int flags, int downMask, int upMask, int button) {
        if ((flags & downMask) != 0) {
            dispatch(RawInputEvent.button(path, button, true));
        }
        if ((flags & upMask) != 0) {
            dispatch(RawInputEvent.button(path, button, false));
        }
    }

    private void dispatch(RawInputEvent event) {
        EMITTED.incrementAndGet();
        for (RawInputListener l : listeners) {
            try {
                l.onRawInput(event);
            } catch (RuntimeException ignored) {
                // never let a listener kill the input thread
            }
        }
    }

    /** WM_INPUT messages the sink has seen since start. */
    public static long messagesSeen() {
        return MESSAGES.get();
    }

    /** Events the sink has handed to listeners since start. */
    public static long eventsEmitted() {
        return EMITTED.get();
    }

    private String pathFor(long deviceHandle) {
        String cached = pathCache.get(deviceHandle);
        if (cached != null) {
            return cached;
        }
        if (!Platform.isWindows()) {
            return null;
        }
        String path = RawInputDevice.deviceName(Pointer.createConstant(deviceHandle));
        if (path != null) {
            pathCache.put(deviceHandle, path);
        }
        return path;
    }

    /** JNA callback for the hidden window. */
    public final class SinkProc implements Callback {
        public WinDef.LRESULT callback(WinDef.HWND hwnd, int msg, WinDef.WPARAM wParam, WinDef.LPARAM lParam) {
            return onMessage(hwnd, msg, wParam, lParam);
        }
    }
}
