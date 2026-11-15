package cn.splitplay.probe;

import cn.splitplay.client.DeviceScreenLayout;
import cn.splitplay.client.TitleButtonLayout;

/**
 * Offline check for where the device picker puts its rows and buttons.
 *
 * <p>The picker is the only way to assign a keyboard and a mouse, so a wrong coordinate is not cosmetic:
 * a row past the bottom edge cannot be clicked, and a row that reaches into the Save/Cancel strip is
 * actively misleading, because the buttons were added later and swallow the click. The split layout makes
 * this concrete — 1920x540 at GUI scale 2 is 960x270 in GUI units, which fits six device rows and no more.
 */
public final class DeviceScreenCheck {

    private static int pass;
    private static int fail;

    private DeviceScreenCheck() {
    }

    public static void main(String[] args) {
        splitWindow();
        titleWindow();
        smallWindow();
        degenerate();
        rowHits();
        overflow();
        invariants();
        titleButtons();
        System.out.println("devicescreen: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** The second window right after the launcher placed it: 1920x540 at GUI scale 2. */
    private static void splitWindow() {
        DeviceScreenLayout layout = DeviceScreenLayout.of(960, 270);
        check("split window fits six rows", 6, layout.visibleRows());
        check("split window keeps the full column width", 190, layout.columnWidth());
        check("keyboard column starts left of centre", 284, layout.keyboardX());
        check("keyboard column ends before the gap", 474, layout.keyboardRight());
        check("mouse column starts right of the gap", 486, layout.mouseX());
        check("mouse column stays on screen", 676, layout.mouseRight());
        check("rows stop above the slider row", 188, layout.rowsBottom());
        check("sliders sit between rows and buttons", 208, layout.sliderY());
        check("slider row clears the rows", Boolean.TRUE,
                layout.rowsBottom() <= layout.sliderY());
        check("slider row clears the buttons", Boolean.TRUE,
                layout.sliderY() + 20 <= layout.buttonY());
        check("buttons sit on the strip", 230, layout.buttonY());
        check("save button is left of cancel", 326, layout.saveX());
        check("cancel button is right of save", 484, layout.cancelX());
        check("save and cancel do not overlap", Boolean.TRUE,
                layout.saveX() + 150 <= layout.cancelX());
    }

    /** A 1280x720 window at GUI scale 3, the size the first window starts at. */
    private static void titleWindow() {
        DeviceScreenLayout layout = DeviceScreenLayout.of(426, 240);
        check("scale 3 window fits five rows", 5, layout.visibleRows());
        check("scale 3 columns keep their width", 190, layout.columnWidth());
        check("scale 3 keyboard column starts on screen", 17, layout.keyboardX());
        check("scale 3 mouse column stays on screen", 409, layout.mouseRight());
        check("scale 3 mouse column fits", Boolean.TRUE, layout.mouseRight() <= layout.width());
        check("scale 3 rows stop above the sliders", 166, layout.rowsBottom());
        check("scale 3 buttons sit on the strip", 200, layout.buttonY());
        check("scale 3 sliders clear the rows", Boolean.TRUE,
                layout.rowsBottom() <= layout.sliderY());
        check("scale 3 sliders clear the buttons", Boolean.TRUE,
                layout.sliderY() + 20 <= layout.buttonY());
        check("scale 3 rows never reach the buttons", Boolean.TRUE,
                layout.rowsBottom() <= layout.buttonY());
    }

    /** The smallest GUI Minecraft allows, 320x240. */
    private static void smallWindow() {
        DeviceScreenLayout layout = DeviceScreenLayout.of(320, 240);
        check("small window narrows the columns", 150, layout.columnWidth());
        check("small window keeps the keyboard column on screen", 4, layout.keyboardX());
        check("small window keeps both columns apart", Boolean.TRUE,
                layout.keyboardRight() <= layout.mouseX());
        check("small window keeps the mouse column on screen", Boolean.TRUE,
                layout.mouseRight() <= layout.width());
        check("small window marks itself narrow", Boolean.TRUE, layout.narrow());

        DeviceScreenLayout narrow = DeviceScreenLayout.of(200, 240);
        check("a 200 wide window still fits both columns", Boolean.TRUE,
                narrow.mouseRight() <= narrow.width());
        check("a 200 wide window keeps the columns apart", Boolean.TRUE,
                narrow.keyboardRight() <= narrow.mouseX());
    }

    /** Numbers that cannot happen should not throw; they only produce an empty screen. */
    private static void degenerate() {
        DeviceScreenLayout none = DeviceScreenLayout.of(0, 0);
        check("an empty screen fits no rows", 0, none.visibleRows());
        check("an empty screen has no column width", 0, none.columnWidth());
        check("an empty screen hits no row", -1, none.rowAt(-1));
        check("an empty screen hides nothing it was given", 0, none.hidden(0));
        check("an empty screen cannot fit a device", Boolean.FALSE, none.fits(1));

        DeviceScreenLayout tall = DeviceScreenLayout.of(320, 100);
        check("a short window fits no rows at all", 0, tall.visibleRows());
        check("a short window hides every device", 3, tall.hidden(3));
    }

    private static void rowHits() {
        DeviceScreenLayout layout = DeviceScreenLayout.of(960, 270);
        check("first row starts at the top of the list", 56, layout.rowY(0));
        check("the top of row zero is a hit", 0, layout.rowAt(56));
        check("just above row zero is not a hit", -1, layout.rowAt(55));
        check("row zero spans its whole height", 0, layout.rowAt(56 + 21));
        check("the next row starts after it", 1, layout.rowAt(56 + 22));
        check("the last visible row is a hit", 5, layout.rowAt(layout.rowY(5) + 21));
        check("below the rows is not a hit", -1, layout.rowAt(layout.rowsBottom()));
        check("the button strip is not a hit", -1, layout.rowAt(layout.buttonY()));
        check("a negative coordinate is not a hit", -1, layout.rowAt(-5));
    }

    private static void overflow() {
        DeviceScreenLayout layout = DeviceScreenLayout.of(960, 270);
        check("a full list fits exactly", Boolean.TRUE, layout.fits(6));
        check("one more than fits does not", Boolean.FALSE, layout.fits(7));
        check("a full list hides nothing", 0, layout.hidden(6));
        check("one more device is hidden", 1, layout.hidden(7));
        check("ten devices leave three hidden", 3, layout.hidden(9));
        check("an empty list hides nothing", 0, layout.hidden(0));
        check("an empty list fits", Boolean.TRUE, layout.fits(0));
    }

    /**
     * The properties that have to hold at every size, not just the three sizes above: columns apart, both
     * on screen, rows above the buttons, buttons on screen.
     */
    private static void invariants() {
        int violations = 0;
        for (int width : new int[] {200, 240, 320, 360, 426, 480, 640, 854, 960, 1280}) {
            for (int height : new int[] {140, 200, 240, 270, 300, 400}) {
                DeviceScreenLayout layout = DeviceScreenLayout.of(width, height);
                if (layout.keyboardX() < 0) {
                    violations++;
                    System.out.println("  columns off screen left at " + width + "x" + height);
                }
                if (layout.keyboardRight() > layout.mouseX()) {
                    violations++;
                    System.out.println("  columns overlap at " + width + "x" + height);
                }
                if (layout.mouseRight() > width) {
                    violations++;
                    System.out.println("  columns off screen right at " + width + "x" + height);
                }
                if (layout.rowsBottom() > layout.sliderY()) {
                    violations++;
                    System.out.println("  rows reach the sliders at " + width + "x" + height);
                }
                if (layout.sliderY() + 20 > layout.buttonY()) {
                    violations++;
                    System.out.println("  sliders reach the buttons at " + width + "x" + height);
                }
                if (layout.buttonY() + 20 > height) {
                    violations++;
                    System.out.println("  buttons below the screen at " + width + "x" + height);
                }
                if (width >= 320 && (layout.saveX() < 0 || layout.cancelX() + 150 > width)) {
                    violations++;
                    System.out.println("  save or cancel off screen at " + width + "x" + height);
                }
            }
        }
        check("layout invariants hold at 60 window sizes", 0, violations);
    }

    /** The two buttons on the title screen follow the GUI area and never leave it. */
    private static void titleButtons() {
        TitleButtonLayout.Rect two = TitleButtonLayout.twoPlayers(960, 270);
        TitleButtonLayout.Rect devices = TitleButtonLayout.devices(960, 270);
        check("title buttons sit above the bottom edge", 244, two.y());
        check("title buttons have the same row", two.y(), devices.y());
        check("two players button is at the left margin", 6, two.x());
        check("devices button follows the first one", 120, devices.x());
        check("devices button is 90 wide", 90, devices.width());

        // 320x180 in GUI units: the pair still fits with room to spare.
        check("devices fits at 320 wide", 120, TitleButtonLayout.devices(320, 180).x());
        // 213 wide (a 640x360 window at GUI scale 3): the pair barely fits, no clipping.
        check("devices pulled left at 213 wide", 117, TitleButtonLayout.devices(213, 120).x());
        check("devices stays inside at 213 wide", true, TitleButtonLayout.devices(213, 120).right() <= 213);
        // 100 wide: cannot fit both, the devices button still stays on screen.
        check("devices stays inside at 100 wide", true, TitleButtonLayout.devices(100, 120).right() <= 100);
        check("devices does not go past the left margin", 6, TitleButtonLayout.devices(100, 120).x());
        // Very short window: the row does not go above the top edge either.
        check("row keeps its margin on a 60 high window", 34, TitleButtonLayout.twoPlayers(320, 60).y());
        check("row clamped on a 30 high window", 4, TitleButtonLayout.twoPlayers(320, 30).y());
        check("row stays inside a 20 high window", true, TitleButtonLayout.twoPlayers(320, 20).bottom() <= 20);

        int violations = 0;
        for (int width = 60; width <= 1000; width += 20) {
            for (int height = 20; height <= 600; height += 20) {
                for (TitleButtonLayout.Rect rect : new TitleButtonLayout.Rect[]{
                        TitleButtonLayout.twoPlayers(width, height),
                        TitleButtonLayout.devices(width, height)}) {
                    if (rect.x() < 0 || rect.y() < 0) {
                        violations++;
                        System.out.println("  button above the origin at " + width + "x" + height);
                    }
                    if (rect.bottom() > height) {
                        violations++;
                        System.out.println("  button below the window at " + width + "x" + height);
                    }
                    if (width >= TitleButtonLayout.TWO_PLAYERS_WIDTH + TitleButtonLayout.DEVICES_WIDTH
                            + 2 * TitleButtonLayout.MARGIN + TitleButtonLayout.GAP
                            && rect.right() > width) {
                        violations++;
                        System.out.println("  button off the right edge at " + width + "x" + height);
                    }
                }
            }
        }
        check("title button invariants hold at 47x30 window sizes", 0, violations);
    }

    private static void check(String what, Object expected, Object actual) {
        boolean ok = expected instanceof Number number
                ? actual instanceof Number other && number.longValue() == other.longValue()
                : expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
            System.out.println("  ok  " + what);
        } else {
            fail++;
            System.out.println("  FAIL " + what + ": expected <" + expected + "> got <" + actual + ">");
        }
    }
}
