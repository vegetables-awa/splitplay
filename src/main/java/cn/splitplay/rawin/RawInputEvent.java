package cn.splitplay.rawin;

/** A single decoded raw-input event, already attributed to the device that produced it. */
public final class RawInputEvent {

    public enum Kind { KEY, BUTTON, MOVE, WHEEL }

    public final Kind kind;
    public final String devicePath;
    /** KEY: win32 virtual-key code. */
    public final int vkey;
    /** KEY: hardware scan code (useful when the keyboard layout mangles VKey). */
    public final int scancode;
    /** KEY / BUTTON: true when pressed. */
    public final boolean down;
    /** BUTTON: 0=left 1=right 2=middle 3=back 4=forward. */
    public final int button;
    /** MOVE: relative movement. WHEEL: dy = wheel delta in raw units (120 per notch). */
    public final int dx;
    public final int dy;
    public final long timeNanos = System.nanoTime();

    private RawInputEvent(Kind kind, String devicePath, int vkey, int scancode, boolean down,
                          int button, int dx, int dy) {
        this.kind = kind;
        this.devicePath = devicePath;
        this.vkey = vkey;
        this.scancode = scancode;
        this.down = down;
        this.button = button;
        this.dx = dx;
        this.dy = dy;
    }

    public static RawInputEvent key(String path, int vkey, int scancode, boolean down) {
        return new RawInputEvent(Kind.KEY, path, vkey, scancode, down, -1, 0, 0);
    }

    public static RawInputEvent button(String path, int button, boolean down) {
        return new RawInputEvent(Kind.BUTTON, path, 0, 0, down, button, 0, 0);
    }

    public static RawInputEvent move(String path, int dx, int dy) {
        return new RawInputEvent(Kind.MOVE, path, 0, 0, false, -1, dx, dy);
    }

    public static RawInputEvent wheel(String path, int horizontal, int vertical) {
        return new RawInputEvent(Kind.WHEEL, path, 0, 0, false, -1, horizontal, vertical);
    }

    @Override
    public String toString() {
        switch (kind) {
            case KEY:
                return "KEY vk=0x" + Integer.toHexString(vkey) + " sc=0x" + Integer.toHexString(scancode)
                        + (down ? " down" : " up") + " dev=" + RawInputSupport.shortDevice(devicePath);
            case BUTTON:
                return "BUTTON b=" + button + (down ? " down" : " up")
                        + " dev=" + RawInputSupport.shortDevice(devicePath);
            case MOVE:
                return "MOVE d=(" + dx + "," + dy + ") dev=" + RawInputSupport.shortDevice(devicePath);
            case WHEEL:
                return "WHEEL d=(" + dx + "," + dy + ") dev=" + RawInputSupport.shortDevice(devicePath);
            default:
                return kind.name();
        }
    }
}
