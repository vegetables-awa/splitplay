package cn.splitplay.config;

import cn.splitplay.util.DevicePath;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-instance settings, stored as json next to the other mod configs.
 *
 * <p>Two game windows run the same mod, so the file has to say which window this is and which two
 * devices belong to it. Device identity is the raw-input interface path
 * (for example {@code \\?\HID#VID_1C4F&PID_0002&MI_00#7&...}) because it is stable across
 * launches and across processes; product names and vendor ids are only used to give the user
 * something readable.
 */
public final class SplitConfig {

    // Plain slf4j instead of Minecraft's LogUtils: this class is also compiled and exercised by the
    // offline config check, which runs without a game on the class path.
    private static final Logger LOGGER = LoggerFactory.getLogger("splitplay");
    // Device paths contain '&', which Gson escapes to \u0026 by default; keep the file readable.
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String FILE_NAME = "splitplay.json";

    /** Free-form label for this window, e.g. "P1" or "P2". Only used in logs. */
    public String role = "";

    /** Raw-input device path of this window's keyboard. Empty means "not assigned yet". */
    public String keyboard = "";

    /** Raw-input device path of this window's mouse. Empty means "not assigned yet". */
    public String mouse = "";

    /** Mouse look multiplier. 1.0 keeps the movement the device reports. */
    public double sensitivity = 1.0D;

    /** Wether routing is active. Off keeps the window completely vanilla. */
    public boolean enabled = true;

    /** How the two windows are stacked: "vertical" (top/bottom) or "horizontal" (left/right). */
    public String layout = "vertical";

    public boolean hasKeyboard() {
        return keyboard != null && !keyboard.isEmpty();
    }

    public boolean hasMouse() {
        return mouse != null && !mouse.isEmpty();
    }

    public boolean isComplete() {
        return hasKeyboard() && hasMouse();
    }

    /**
     * True when this window should filter native input and replay its own devices instead. Without
     * both devices assigned there is nothing to replay, so the window stays vanilla rather than
     * becoming uncontrollable.
     */
    public boolean isolatesInput() {
        return enabled && isComplete();
    }

    /** True when the given raw-input path is this window's keyboard. */
    public boolean matchesKeyboard(String path) {
        return sameDevice(keyboard, path);
    }

    /** True when the given raw-input path is this window's mouse. */
    public boolean matchesMouse(String path) {
        return sameDevice(mouse, path);
    }

    public static Path defaultFile(Path configDir) {
        return configDir.resolve(FILE_NAME);
    }

    /**
     * Loads the config, or writes a default one when the file is missing or unreadable. A broken
     * file is never fatal: the game must still start, otherwise the user cannot reach the device
     * selection screen that would fix it.
     */
    public static SplitConfig load(Path file) {
        if (file == null) {
            return new SplitConfig();
        }
        if (!Files.isRegularFile(file)) {
            SplitConfig fresh = new SplitConfig();
            fresh.save(file);
            return fresh;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            SplitConfig parsed = GSON.fromJson(reader, SplitConfig.class);
            if (parsed == null) {
                LOGGER.warn("splitplay: {} is empty, using defaults", file);
                return new SplitConfig();
            }
            parsed.normalise();
            return parsed;
        } catch (IOException | RuntimeException e) {
            // RuntimeException covers every shape of bad json (syntax, wrong type, wrong root), not
            // only JsonSyntaxException: a hand-edited file must never stop the game from starting.
            LOGGER.warn("splitplay: cannot read {} ({}), using defaults", file, e.toString());
            return new SplitConfig();
        }
    }

    public void save(Path file) {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("splitplay: cannot write {} ({})", file, e.toString());
        }
    }

    /** Clamps values that a hand-edited file could put out of range. */
    private void normalise() {
        if (sensitivity < 0.1D) {
            sensitivity = 0.1D;
        } else if (sensitivity > 10.0D) {
            sensitivity = 10.0D;
        }
        if (keyboard == null) {
            keyboard = "";
        }
        if (mouse == null) {
            mouse = "";
        }
        if (role == null) {
            role = "";
        }
        if (layout == null || layout.isEmpty()) {
            layout = "vertical";
        }
    }

    /** Compares two device paths; see {@link DevicePath#matches(String, String)}. */
    private static boolean sameDevice(String configured, String candidate) {
        return DevicePath.matches(configured, candidate);
    }
}