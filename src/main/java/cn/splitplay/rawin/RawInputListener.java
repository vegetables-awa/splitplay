package cn.splitplay.rawin;

/** Receives decoded raw-input events. Called on the raw-input thread, so keep it short. */
public interface RawInputListener {

    void onRawInput(RawInputEvent event);

    /** A device was plugged in or removed; re-enumerate if you care. */
    default void onDevicesChanged() {
    }
}
