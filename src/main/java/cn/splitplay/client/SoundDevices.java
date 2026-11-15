package cn.splitplay.client;

import cn.splitplay.util.SoundDeviceNames;
import java.util.List;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALUtil;

/**
 * Lists the output devices the game can open, for the sound picker in the device screen.
 *
 * <p>When the driver supports {@code ALC_ENUMERATE_ALL_EXT} every endpoint is listed with the name
 * OpenAL wants back, for example {@code OpenAL Soft on Speakers (Realtek Audio)} - the same names the
 * vanilla sound screen offers. Without the extension only the driver name is available, which is what
 * the fallback returns. Nothing here opens a device, so the list also works before the sound engine
 * starts; any failure degrades to an empty list and the screen offers "Default" only.</p>
 */
public final class SoundDevices {

    /** {@code ALC_ALL_DEVICES_SPECIFIER}; ALC10 does not expose the constant. */
    private static final int ALL_DEVICES_SPECIFIER = 0x1013;

    private SoundDevices() {
    }

    public static List<String> available() {
        try {
            List<String> all = ALUtil.getStringList(0L, ALL_DEVICES_SPECIFIER);
            if (all != null && !all.isEmpty()) {
                return all;
            }
        } catch (Throwable ignored) {
            // Fall through to the plain device specifier.
        }
        try {
            return SoundDeviceNames.parse(ALC10.alcGetString(0L, ALC10.ALC_DEVICE_SPECIFIER));
        } catch (Throwable failure) {
            return List.of();
        }
    }
}
