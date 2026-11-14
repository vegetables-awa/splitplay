package cn.splitplay.rawin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small helpers shared by the raw-input classes. */
public final class RawInputSupport {

    private static final Pattern VID_PID = Pattern.compile("VID_([0-9A-Fa-f]{4})&PID_([0-9A-Fa-f]{4})");

    private RawInputSupport() {
    }

    /** {@code \\?\HID#VID_1C4F&PID_0002&MI_00#7&...#{guid}} -> {@code VID_1C4F&PID_0002} */
    public static String shortDevice(String path) {
        if (path == null) {
            return "<null>";
        }
        Matcher m = VID_PID.matcher(path);
        if (m.find()) {
            return "VID_" + m.group(1) + "&PID_" + m.group(2);
        }
        String p = path.startsWith("\\\\?\\") ? path.substring(4) : path;
        int hash = p.indexOf('#');
        return hash > 0 ? p.substring(0, hash) : p;
    }
}
