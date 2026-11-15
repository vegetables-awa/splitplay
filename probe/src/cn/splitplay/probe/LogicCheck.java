package cn.splitplay.probe;

import cn.splitplay.cursor.CursorGeometry;
import cn.splitplay.cursor.PointerPolicy;
import cn.splitplay.launch.MainWindowPicker;
import cn.splitplay.launch.SplitLayout;
import cn.splitplay.util.DevicePath;
import cn.splitplay.util.SoundDeviceNames;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline check for the pieces of pure logic that decide where things land on screen: the drawn
 * pointer, the split of the screen into two windows, which window belongs to which game process, and how
 * device paths are compared. All four have broken production behaviour before: an arrow that leaves the
 * window at one GUI scale, paths that only matched when the prefix happened to agree, a window split that
 * was never exercised for the other layout, and a window filter that cannot be debugged from a symptom.
 */
public final class LogicCheck {

    private static int pass;
    private static int fail;

    private LogicCheck() {
    }

    public static void main(String[] args) {
        arrowShape();
        placement();
        splitLayouts();
        mainWindowPick();
        devicePaths();
        pointerPolicy();
        soundDevices();
        System.out.println("logic: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * The pointer states. Each row is a distinct situation on the shared system pointer, and getting
     * one wrong is how the pointer ends up on top of another application or clicks something behind
     * the game.
     */
    private static void pointerPolicy() {
        PointerPolicy off = PointerPolicy.decide(false, true, false);
        check("inactive: no trap", false, off.trap());
        check("inactive: no hide", false, off.hide());
        check("inactive: no grab", false, off.grabbedInWorld());

        PointerPolicy parked = PointerPolicy.decide(true, false, false);
        check("unfocused: no trap", false, parked.trap());
        check("unfocused: pointer left alone", false, parked.hide());
        check("unfocused: world still plays", true, parked.grabbedInWorld());

        PointerPolicy parkedMenu = PointerPolicy.decide(true, false, true);
        check("unfocused menu: no trap", false, parkedMenu.trap());
        check("unfocused menu: no grab", false, parkedMenu.grabbedInWorld());

        PointerPolicy menu = PointerPolicy.decide(true, true, true);
        check("menu: trap", true, menu.trap());
        check("menu: hide", true, menu.hide());
        check("menu: clips", true, menu.clipsPointer());
        check("menu: no grab", false, menu.grabbedInWorld());

        PointerPolicy world = PointerPolicy.decide(true, true, false);
        check("world: trap", true, world.trap());
        check("world: pointer not shown", true, world.hide());
        check("world: grab for camera", true, world.grabbedInWorld());
    }

    private static MainWindowPicker.Candidate window(long pid, long handle, String title, int w, int h,
            boolean visible) {
        return new MainWindowPicker.Candidate(pid, handle, title, w, h, visible);
    }

    private static void mainWindowPick() {
        // A real game process owns the framed main window plus a splash window and an invisible helper.
        List<MainWindowPicker.Candidate> game = List.of(
                window(100, 1, "Minecraft* 1.21.1", 1920, 1080, true),
                window(100, 2, "Minecraft* 1.21.1", 320, 240, false),
                window(100, 3, "", 1920, 1080, true),
                window(100, 4, "  ", 800, 600, true));
        check("largest visible titled window wins", 1L, MainWindowPicker.pick(game).get(100L));
        check("only the asked process is reported", 1, MainWindowPicker.pick(game).size());

        List<MainWindowPicker.Candidate> hiddenOnly = List.of(
                window(7, 11, "Splash", 800, 600, false));
        check("an invisible window is not picked", null, MainWindowPicker.pick(hiddenOnly).get(7L));

        List<MainWindowPicker.Candidate> untitled = List.of(
                window(7, 12, "", 800, 600, true),
                window(7, 13, null, 800, 600, true));
        check("untitled windows are not picked", 0, MainWindowPicker.pick(untitled).size());

        List<MainWindowPicker.Candidate> zeroSize = List.of(
                window(7, 14, "Minecraft", 0, 0, true),
                window(7, 15, "Minecraft", -5, 600, true));
        check("windows without area are not picked", 0, MainWindowPicker.pick(zeroSize).size());

        List<MainWindowPicker.Candidate> twoPids = List.of(
                window(1, 21, "top", 1920, 540, true),
                window(2, 22, "bottom", 1920, 540, true),
                // A splash window is smaller than the game window in practice; if it were not, the
                // heuristic would take it, which is why the sizes matter here.
                window(2, 23, "splash", 640, 480, true));
        Map<Long, Long> picked = MainWindowPicker.pick(twoPids);
        check("both game processes are reported", 2, picked.size());
        check("first process keeps its window", 21L, picked.get(1L));
        check("second process keeps the game window, not the splash", 22L, picked.get(2L));

        List<MainWindowPicker.Candidate> biggerSplash = List.of(
                window(9, 31, "game", 1920, 540, true),
                window(9, 32, "loading", 1920, 1080, true));
        check("the largest window wins even when it is not the game window", 32L,
                MainWindowPicker.pick(biggerSplash).get(9L));

        check("nothing to pick is empty", 0, MainWindowPicker.pick(List.of()).size());
    }

    private static void splitLayouts() {
        SplitLayout stacked = SplitLayout.of(1920, 1080, false);
        check("stacked first half", 0, stacked.first().y());
        check("stacked first height", 540, stacked.first().height());
        check("stacked second top", 540, stacked.second().y());
        check("stacked second height", 540, stacked.second().height());
        check("stacked first is full width", 1920, stacked.first().width());
        check("stacked tiles the screen", true, stacked.tiles(1920, 1080));

        SplitLayout side = SplitLayout.of(1920, 1080, true);
        check("side by side first width", 960, side.first().width());
        check("side by side second left", 960, side.second().x());
        check("side by side is full height", 1080, side.second().height());
        check("side by side tiles the screen", true, side.tiles(1920, 1080));

        // Odd dimensions used to be the interesting case: an integer half leaves a one pixel strip of
        // desktop between the two players if the second rectangle is computed the same way.
        SplitLayout oddHeight = SplitLayout.of(1920, 1081, false);
        check("odd height tiles the screen", true, oddHeight.tiles(1920, 1081));
        check("odd height second half takes the remainder", 541, oddHeight.second().height());

        SplitLayout oddWidth = SplitLayout.of(1921, 1080, true);
        check("odd width tiles the screen", true, oddWidth.tiles(1921, 1080));
        check("odd width second half takes the remainder", 961, oddWidth.second().width());

        SplitLayout tiny = SplitLayout.of(0, 0, false);
        check("degenerate screen still has two windows", 2,
                (tiny.first().width() > 0 ? 1 : 0) + (tiny.second().width() > 0 ? 1 : 0));
        check("degenerate screen is not negative", true, tiny.first().height() >= 0 && tiny.second().height() >= 0);

        SplitLayout common = SplitLayout.of(1366, 768, false);
        check("1366x768 tiles the screen", true, common.tiles(1366, 768));
        SplitLayout commonSide = SplitLayout.of(1366, 768, true);
        check("1366x768 side by side tiles the screen", true, commonSide.tiles(1366, 768));
    }

    private static void arrowShape() {
        check("arrow row count", CursorGeometry.HEIGHT, CursorGeometry.ARROW.length);
        boolean ordered = true;
        int widest = -1;
        for (int[] row : CursorGeometry.ARROW) {
            if (row[0] < 0 || row[1] < row[0] || row[1] >= CursorGeometry.WIDTH) {
                ordered = false;
            }
            widest = Math.max(widest, row[1]);
        }
        check("every row inside the arrow box", true, ordered);
        check("width covers the widest row", CursorGeometry.WIDTH - 1, widest);

        Set<String> distinct = new HashSet<>();
        for (int[] offset : CursorGeometry.OUTLINE) {
            distinct.add(offset[0] + "," + offset[1]);
            check("outline offset is one pixel", true,
                    Math.abs(offset[0]) <= 1 && Math.abs(offset[1]) <= 1);
        }
        check("eight distinct outline offsets", 8, distinct.size());
    }

    private static void placement() {
        check("toGui rounds", 3, CursorGeometry.toGui(5.0D, 2.0D));
        check("toGui at zero", 0, CursorGeometry.toGui(0.0D, 3.0D));
        check("toGui guards zero scale", 0, CursorGeometry.toGui(100.0D, 0.0D));

        int guiWidth = 960;
        int guiHeight = 270;
        check("left edge stays visible", 0, CursorGeometry.origin(0.0D, 2.0D, CursorGeometry.WIDTH, guiWidth));
        check("negative position clamps", 0,
                CursorGeometry.origin(-50.0D, 2.0D, CursorGeometry.WIDTH, guiWidth));
        check("middle", 320, CursorGeometry.origin(640.0D, 2.0D, CursorGeometry.WIDTH, guiWidth));
        check("right edge clamps", guiWidth - CursorGeometry.WIDTH - 1,
                CursorGeometry.origin(1918.0D, 2.0D, CursorGeometry.WIDTH, guiWidth));
        check("bottom edge clamps", guiHeight - CursorGeometry.HEIGHT - 1,
                CursorGeometry.origin(1000.0D, 2.0D, CursorGeometry.HEIGHT, guiHeight));

        int maxX = CursorGeometry.maxOrigin(guiWidth, CursorGeometry.WIDTH);
        check("clamped arrow is inside", true,
                CursorGeometry.inside(maxX, CursorGeometry.WIDTH, guiWidth));
        check("one pixel margin is kept", CursorGeometry.WIDTH + 1, guiWidth - maxX);
        check("beyond the margin is outside", false,
                CursorGeometry.inside(maxX + 2, CursorGeometry.WIDTH, guiWidth));

        // 1280x720 at GUI scale 3 -> 426x240, the size the title screen really runs at.
        int small = 426;
        check("scale 3 clamps into a narrow window", CursorGeometry.maxOrigin(small, CursorGeometry.WIDTH),
                CursorGeometry.origin(1279.0D, 3.0D, CursorGeometry.WIDTH, small));
        check("tiny window still inside", true, CursorGeometry.inside(
                CursorGeometry.origin(500.0D, 2.0D, CursorGeometry.HEIGHT, 20), CursorGeometry.HEIGHT, 20));
    }

    private static void devicePaths() {
        String raw = "\\\\?\\HID#VID_2A7A&PID_8A57&MI_00#7&1288512d&0&0000#{884b96c3-56ef-11d1-bc8c-00a0c91405dd}";
        String plain = "hid#vid_2a7a&pid_8a57&mi_00#7&1288512d&0&0000#{884b96c3-56ef-11d1-bc8c-00a0c91405dd}";
        check("normalise null", "", DevicePath.normalise(null));
        check("normalise strips prefix", plain, DevicePath.normalise(raw));
        check("normalise is idempotent", plain, DevicePath.normalise(plain));
        check("normalise trims", plain, DevicePath.normalise("  " + raw + "  "));
        check("normalise keeps inner ampersand", true, DevicePath.normalise(raw).contains("&pid_8a57"));

        check("match identical", true, DevicePath.matches(raw, raw));
        check("match prefixed against plain", true, DevicePath.matches(raw, plain));
        check("match is case insensitive", true, DevicePath.matches(plain, raw.toUpperCase(java.util.Locale.ROOT)));
        check("truncated start of the path is not matched", false,
                DevicePath.matches("hid#vid_2a7a&pid_8a57", raw));
        check("long suffix of the path is matched", true,
                DevicePath.matches("mi_00#7&1288512d&0&0000#{884b96c3-56ef-11d1-bc8c-00a0c91405dd}", raw));
        check("tiny suffix is not matched", false, DevicePath.matches("8a57", raw));
        // Same model re-plugged into another port: the instance part changes, so the saved path stops
        // matching. Deliberate: two identical keyboards differ the same way, and guessing would hand
        // one player the other player's keyboard. The window falls back to vanilla input instead.
        check("re-plugged instance is not matched", false,
                DevicePath.matches(raw, plain.replace("1288512d", "3f9c1a04")));
        check("no match for other device", false,
                DevicePath.matches(raw, plain.replace("8a57", "8a58")));
        check("empty configured never matches", false, DevicePath.matches("", raw));
        check("empty candidate never matches", false, DevicePath.matches(raw, ""));
        check("null never matches", false, DevicePath.matches(null, raw));
    }

    private static void soundDevices() {
        check("no device list", 0, SoundDeviceNames.parse(null).size());
        check("empty device list", 0, SoundDeviceNames.parse("").size());
        check("only the terminator", 0, SoundDeviceNames.parse("\0\0").size());
        check("one device", List.of("Speakers"), SoundDeviceNames.parse("Speakers\0\0"));
        check("two devices", List.of("A", "B"), SoundDeviceNames.parse("A\0B\0\0"));
        check("names keep spaces and brackets", List.of("Speakers (USB Audio)"),
                SoundDeviceNames.parse("Speakers (USB Audio)\0\0"));
        check("missing terminator still yields the name", List.of("A"), SoundDeviceNames.parse("A"));
    }

    private static void check(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + name + ": expected <" + expected + "> got <" + actual + ">");
        }
    }
}
