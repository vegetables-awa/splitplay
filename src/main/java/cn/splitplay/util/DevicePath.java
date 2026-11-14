package cn.splitplay.util;

import java.util.Locale;

/**
 * Device path helpers shared by the config, the device screen and the cross-window claims.
 *
 * <p>Windows is not consistent about how it spells the same device: raw input adds a {@code \\?\}
 * prefix, letter case varies, and the instance ordinal can change after a re-plug. Everything that
 * compares paths has to normalise both sides, and getting that wrong silently breaks the "already in
 * use by the other window" check.</p>
 */
public final class DevicePath {

    private DevicePath() {
    }

    /** Lower case, trimmed, without the {@code \\?\} prefix raw input adds. */
    public static String normalise(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        if (p.startsWith("\\\\?\\")) {
            p = p.substring(4);
        }
        return p.toLowerCase(Locale.ROOT);
    }

    /**
     * Shortest suffix that still identifies one device; below this, a suffix such as
     * {@code 8a57} would match unrelated devices.
     */
    private static final int MIN_SUFFIX = 8;

    /**
     * True when both strings describe the same device.
     *
     * <p>Matching is deliberately strict: exact (after normalising) or a suffix longer than
     * {@link #MIN_SUFFIX}. Fuzzy matching was tried and removed. Windows rewrites the instance part
     * of the path after a re-plug ({@code 7&1288512d&0&0000} becomes {@code 7&3f9c1a04&0&0000}),
     * which leaves a long common beginning - but two <em>identical</em> keyboards differ in exactly
     * the same way, so accepting the common beginning would silently hand one player the other
     * player's keyboard. A path that stops matching is loud and harmless instead: the window falls
     * back to vanilla input (see {@code SplitPlayClient.assignedDevicesPresent}) and the user picks
     * the devices again.</p>
     */
    public static boolean matches(String configured, String candidate) {
        String a = normalise(configured);
        String b = normalise(candidate);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        // A hand-written or truncated path is still accepted, as long as enough of it survives to
        // identify the device.
        return (b.endsWith(a) && a.length() >= MIN_SUFFIX) || (a.endsWith(b) && b.length() >= MIN_SUFFIX);
    }
}
