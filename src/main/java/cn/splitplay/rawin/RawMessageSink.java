package cn.splitplay.rawin;

import com.sun.jna.Pointer;

/** Receives raw input messages that were addressed to the game window itself. */
public interface RawMessageSink {

    /**
     * A {@code WM_INPUT} message; {@code lParam} is the {@code RAWINPUT} handle.
     *
     * <p>Called on the render thread, inside the window procedure, so implementations must be quick
     * and must never throw.</p>
     */
    void onRawInputMessage(Pointer lParam);

    /** {@code WM_INPUT_DEVICE_CHANGE}; {@code wParam} is {@code GIDC_ARRIVAL} or {@code GIDC_REMOVAL}. */
    void onRawDeviceChange(int wParam, long lParam);
}
