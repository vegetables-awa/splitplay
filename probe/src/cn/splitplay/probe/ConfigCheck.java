package cn.splitplay.probe;

import cn.splitplay.config.SplitConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Offline check of the file every user ends up editing by hand.
 *
 * <p>The config decides whether a window filters input at all, so a wrong default or a crash on a
 * malformed file is not cosmetic: it either leaves a window uncontrollable or stops the game from
 * starting. Everything here runs without Minecraft, only gson and slf4j.</p>
 */
public final class ConfigCheck {

    private static int passed;
    private static int failed;

    private ConfigCheck() {
    }

    public static void main(String[] args) throws IOException {
        // SplitConfig logs through slf4j. No provider is on this class path, so the first logger
        // lookup prints a warning to stderr - which a PowerShell wrapper turns into a red error.
        // The verdict of this check is on stdout and in the exit code, so stderr is dropped.
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        Path dir = Files.createTempDirectory("splitplay-config-check");
        try {
            defaults();
            matching();
            validation(dir);
            roundTrip(dir);
            brokenFiles(dir);
        } finally {
            delete(dir);
        }
        System.out.println("config: pass=" + passed + " fail=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void defaults() {
        SplitConfig config = new SplitConfig();
        check("default role is empty", "".equals(config.role));
        check("default keyboard is empty", "".equals(config.keyboard));
        check("default mouse is empty", "".equals(config.mouse));
        check("default sensitivity is 1", config.sensitivity == 1.0D);
        check("default layout is vertical", "vertical".equals(config.layout));
        check("default is enabled", config.enabled);
        check("nothing assigned is not complete", !config.isComplete());
        check("nothing assigned isolates nothing", !config.isolatesInput());
        check("empty keyboard is not a keyboard", !config.hasKeyboard());

        config.keyboard = "k";
        check("keyboard alone is not complete", !config.isComplete());
        check("keyboard alone inside nothing", !config.isolatesInput());
        config.mouse = "m";
        check("both assigned is complete", config.isComplete());
        check("both assigned isolates", config.isolatesInput());
        config.enabled = false;
        check("disabled does not isolate", !config.isolatesInput());
        check("disabled keeps the devices", config.isComplete());
    }

    private static void matching() {
        SplitConfig config = new SplitConfig();
        config.keyboard = "\\\\?\\HID#VID_1C4F&PID_0002&MI_00#7&b7ed1e6&0&0000#{884b96c3}";
        config.mouse = "\\\\?\\HID#VID_10C4&PID_0005#6&2db35617&0&0000#{378de44c}";

        check("exact keyboard matches", config.matchesKeyboard("\\\\?\\HID#VID_1C4F&PID_0002&MI_00#7&b7ed1e6&0&0000#{884b96c3}"));
        check("keyboard matches without prefix", config.matchesKeyboard("HID#VID_1C4F&PID_0002&MI_00#7&b7ed1e6&0&0000#{884b96c3}"));
        check("keyboard matches in other case", config.matchesKeyboard("hid#vid_1c4f&pid_0002&mi_00#7&b7ed1e6&0&0000#{884b96c3}"));
        // Matching is exact on purpose: after a re-plug the instance part changes, and two identical
        // keyboards look exactly like that, so guessing would mix the two players up.
        check("keyboard does not match after a port change",
                !config.matchesKeyboard("\\\\?\\HID#VID_1C4F&PID_0002&MI_00#7&3f9c1a04&0&0000#{884b96c3}"));
        check("mouse does not match the keyboard path", !config.matchesMouse(config.keyboard));
        check("keyboard does not match another device",
                !config.matchesKeyboard("\\\\?\\HID#VID_2A7A&PID_8A57&MI_00#7&1288512d&0&0000#{884b96c3}"));
        check("empty config matches nothing", !new SplitConfig().matchesKeyboard("anything"));
        check("null candidate matches nothing", !config.matchesKeyboard(null));
    }

    private static void validation(Path dir) throws IOException {
        Path file = dir.resolve("clamped.json");
        Files.writeString(file, "{\"sensitivity\":0.0,\"layout\":\"\",\"role\":null,\"keyboard\":null,"
                + "\"mouse\":null,\"enabled\":true}", StandardCharsets.UTF_8);
        SplitConfig config = SplitConfig.load(file);
        check("sensitivity below 0.1 is clamped up", config.sensitivity == 0.1D);
        check("empty layout falls back to vertical", "vertical".equals(config.layout));
        check("null role becomes empty", "".equals(config.role));
        check("null keyboard becomes empty", "".equals(config.keyboard));
        check("null mouse becomes empty", "".equals(config.mouse));

        Path high = dir.resolve("high.json");
        Files.writeString(high, "{\"sensitivity\":99.0,\"layout\":\"horizontal\",\"enabled\":true}",
                StandardCharsets.UTF_8);
        SplitConfig clamped = SplitConfig.load(high);
        check("sensitivity above 10 is clamped down", clamped.sensitivity == 10.0D);
        check("horizontal layout survives", "horizontal".equals(clamped.layout));
        check("unknown fields are ignored",
                SplitConfig.load(write(dir, "extra.json", "{\"nonsense\":1,\"enabled\":true}")).enabled);
    }

    private static void roundTrip(Path dir) throws IOException {
        SplitConfig original = new SplitConfig();
        original.role = "P2";
        original.keyboard = "\\\\?\\HID#VID_2A7A&PID_8A57&MI_00#7&1288512d&0&0000#{884b96c3}";
        original.mouse = "\\\\?\\HID#VID_30FA&PID_0400#6&13506808&0&0000#{378de44c}";
        original.sensitivity = 1.5D;
        original.layout = "horizontal";

        Path file = dir.resolve("roundtrip.json");
        original.save(file);
        check("save created the file", Files.isRegularFile(file));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        check("saved json keeps '&' readable", text.contains("&PID_"));
        check("saved json does not escape to \\u0026", !text.contains("\\u0026"));
        check("saved json is indented", text.contains("\n  "));

        SplitConfig loaded = SplitConfig.load(file);
        check("role survives", "P2".equals(loaded.role));
        check("keyboard survives", original.keyboard.equals(loaded.keyboard));
        check("mouse survives", original.mouse.equals(loaded.mouse));
        check("sensitivity survives", loaded.sensitivity == 1.5D);
        check("layout survives", "horizontal".equals(loaded.layout));
        check("round trip isolates", loaded.isolatesInput());
    }

    private static void brokenFiles(Path dir) throws IOException {
        Path missing = dir.resolve("nested/deeper/missing.json");
        SplitConfig created = SplitConfig.load(missing);
        check("missing file yields defaults", "".equals(created.keyboard) && created.sensitivity == 1.0D);
        check("missing file is created on disk", Files.isRegularFile(missing));

        check("empty file yields defaults", "".equals(SplitConfig.load(write(dir, "empty.json", "")).keyboard));
        check("truncated json yields defaults",
                !SplitConfig.load(write(dir, "truncated.json", "{\"role\":\"P1\"")).isComplete());
        check("json root of the wrong type yields defaults",
                "".equals(SplitConfig.load(write(dir, "array.json", "[1,2,3]")).role));
        check("wrong value type yields defaults",
                SplitConfig.load(write(dir, "type.json", "{\"sensitivity\":\"lots\"}")).sensitivity == 1.0D);
        check("null root yields defaults",
                SplitConfig.load(write(dir, "null.json", "null")).sensitivity == 1.0D);
        check("load(null) yields defaults", SplitConfig.load(null).sensitivity == 1.0D);
        new SplitConfig().save(null);
        check("save(null) is a no-op", true);
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private static void delete(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover temp file is not worth failing the check over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }
}
