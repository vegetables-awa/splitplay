package cn.splitplay.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the device list OpenAL hands back into names.
 *
 * <p>OpenAL returns one big string with the names separated by a single NUL and the whole list
 * terminated by a second NUL, so splitting on NUL and dropping the trailing empty entry is the whole
 * job. Kept free of OpenAL imports so the offline checks can run it without an audio device.</p>
 */
public final class SoundDeviceNames {

    private SoundDeviceNames() {
    }

    public static List<String> parse(String raw) {
        List<String> names = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return names;
        }
        int start = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '\0') {
                if (i > start) {
                    names.add(raw.substring(start, i));
                }
                start = i + 1;
            }
        }
        if (start < raw.length()) {
            names.add(raw.substring(start));
        }
        return names;
    }
}
