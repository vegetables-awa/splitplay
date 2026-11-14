package cn.splitplay.rawin;

import com.sun.jna.Memory;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One raw-input capable device (a keyboard or a mouse), identified by its device interface path.
 *
 * <p>The path (for example {@code \\?\HID#VID_1C4F&PID_0002&MI_00#7&...#{884b96c3-...}}) is stable
 * across processes and across reboots for the same physical port, which makes it a usable key for
 * persisting "this player owns these devices".</p>
 */
public final class RawInputDevice {

    private static final Pattern VID_PID = Pattern.compile("VID_([0-9A-Fa-f]{4})&PID_([0-9A-Fa-f]{4})");

    public final String path;
    public final int type;          // RawInputApi.RIM_TYPE*
    public final int vendorId;      // -1 when unknown
    public final int productId;     // -1 when unknown

    public RawInputDevice(String path, int type) {
        this.path = path;
        this.type = type;
        Matcher m = VID_PID.matcher(path == null ? "" : path);
        if (m.find()) {
            this.vendorId = Integer.parseInt(m.group(1), 16);
            this.productId = Integer.parseInt(m.group(2), 16);
        } else {
            this.vendorId = -1;
            this.productId = -1;
        }
    }

    public boolean isKeyboard() {
        return type == RawInputApi.RIM_TYPEKEYBOARD;
    }

    public boolean isMouse() {
        return type == RawInputApi.RIM_TYPEMOUSE;
    }

    /** Short label for GUI lists. Never unique on its own - always show the path suffix too. */
    public String shortLabel() {
        String kind = isKeyboard() ? "Keyboard" : isMouse() ? "Mouse" : "HID";
        if (vendorId >= 0) {
            return String.format("%s (VID:%04X PID:%04X)", kind, vendorId, productId);
        }
        return kind;
    }

    /** Last few characters of the instance path, enough to tell two identical models apart. */
    public String pathSuffix() {
        String p = path == null ? "" : path;
        int hash = p.indexOf("#{");
        if (hash > 0) {
            p = p.substring(0, hash);
        }
        int last = p.lastIndexOf('#');
        String tail = last >= 0 && last + 1 < p.length() ? p.substring(last + 1) : p;
        return tail.length() > 12 ? tail.substring(tail.length() - 12) : tail;
    }

    @Override
    public String toString() {
        return shortLabel() + " [" + pathSuffix() + "]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RawInputDevice && java.util.Objects.equals(path, ((RawInputDevice) o).path);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(path);
    }

    // ------------------------------------------------------------------ enumeration

    /**
     * Enumerates every raw-input device the system exposes: keyboards, mice and HID collections.
     * Called from the game thread is fine; it is a couple of cheap user32 calls.
     */
    public static List<RawInputDevice> enumerate() {
        int structSize = new WinUser.RAWINPUTDEVICELIST().size();
        IntByReference count = new IntByReference();
        if (User32.INSTANCE.GetRawInputDeviceList(null, count, structSize) != 0) {
            return Collections.emptyList();
        }
        int n = count.getValue();
        if (n <= 0) {
            return Collections.emptyList();
        }
        WinUser.RAWINPUTDEVICELIST[] list =
                (WinUser.RAWINPUTDEVICELIST[]) new WinUser.RAWINPUTDEVICELIST().toArray(n);
        if (User32.INSTANCE.GetRawInputDeviceList(list, count, structSize) == -1) {
            return Collections.emptyList();
        }
        int got = Math.min(n, count.getValue());
        List<RawInputDevice> out = new ArrayList<>(got);
        for (int i = 0; i < got; i++) {
            WinUser.RAWINPUTDEVICELIST entry = list[i];
            if (entry == null || entry.hDevice == null) {
                continue;
            }
            String path = deviceName(entry.hDevice.getPointer());
            out.add(new RawInputDevice(path, entry.dwType));
        }
        return out;
    }

    /** RIDI_DEVICENAME: the device interface path, or {@code null} if the call fails. */
    public static String deviceName(Pointer hDevice) {
        IntByReference size = new IntByReference(0);
        RawInputApi.INSTANCE.GetRawInputDeviceInfoW(hDevice, RawInputApi.RIDI_DEVICENAME, null, size);
        int chars = size.getValue();
        if (chars <= 0) {
            return null;
        }
        Memory buf = new Memory((long) chars * (Platform.isWindows() ? 2 : 1));
        buf.clear();
        size.setValue(chars);
        if (RawInputApi.INSTANCE.GetRawInputDeviceInfoW(hDevice, RawInputApi.RIDI_DEVICENAME, buf, size) < 0) {
            return null;
        }
        return buf.getWideString(0);
    }
}
