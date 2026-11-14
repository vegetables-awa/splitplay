package cn.splitplay.client;

/**
 * Where the device rows and the two buttons of {@code DeviceScreen} go.
 *
 * <p>Pure arithmetic on purpose: the screen itself cannot be tested without starting a game, and getting
 * this wrong is not cosmetic. Rows that run past the screen are unreachable, and rows that reach into the
 * button strip are worse than unreachable — the buttons are added later, so a click in that strip hits
 * Save instead of the device under it, which makes a device impossible to select while looking selectable.
 *
 * <p>The split windows are short (1920x540 at GUI scale 2 is 960x270 in GUI units, so only seven rows
 * fit), which is exactly the case a hand-written layout forgets.</p>
 */
public final class DeviceScreenLayout {

    /** Height of one device row. */
    public static final int ROW_HEIGHT = 22;

    /** Y of the first row. */
    public static final int ROW_TOP = 56;

    /** Height of a row's button; the rest of the row is a gap. */
    public static final int ROW_BUTTON_HEIGHT = 20;

    /** Preferred column width. */
    public static final int COLUMN_WIDTH = 190;

    /** Gap between the two columns, centred on the middle of the screen. */
    public static final int CENTRE_GAP = 12;

    /** Space at the bottom of the screen reserved for Save and Cancel. */
    public static final int BUTTON_STRIP = 40;

    private final int width;
    private final int height;
    private final int columnWidth;
    private final int keyboardX;
    private final int mouseX;
    private final int visibleRows;
    private final int saveX;
    private final int cancelX;
    private final int buttonY;

    private DeviceScreenLayout(int width, int height) {
        this.width = width;
        this.height = height;
        // Never let a column reach past the screen: a narrower column is bad, an off-screen one is worse
        // because its rows cannot be clicked at all.
        int usable = width - CENTRE_GAP - 8;
        this.columnWidth = Math.max(0, Math.min(COLUMN_WIDTH, usable / 2));
        this.keyboardX = width / 2 - CENTRE_GAP / 2 - columnWidth;
        this.mouseX = width / 2 + CENTRE_GAP / 2;
        int rowsSpace = height - BUTTON_STRIP - ROW_TOP;
        this.visibleRows = Math.max(0, rowsSpace / ROW_HEIGHT);
        this.buttonY = height - BUTTON_STRIP;
        this.saveX = width / 2 - 154;
        this.cancelX = width / 2 + 4;
    }

    public static DeviceScreenLayout of(int width, int height) {
        return new DeviceScreenLayout(Math.max(0, width), Math.max(0, height));
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int columnWidth() {
        return columnWidth;
    }

    /** Left edge of the keyboard column. */
    public int keyboardX() {
        return keyboardX;
    }

    /** Left edge of the mouse column. */
    public int mouseX() {
        return mouseX;
    }

    /** Right edge of the keyboard column (exclusive). */
    public int keyboardRight() {
        return keyboardX + columnWidth;
    }

    /** Right edge of the mouse column (exclusive). */
    public int mouseRight() {
        return mouseX + columnWidth;
    }

    /** How many rows fit above the button strip. */
    public int visibleRows() {
        return visibleRows;
    }

    /** Y of the {@code index}-th visible row. */
    public int rowY(int index) {
        return ROW_TOP + index * ROW_HEIGHT;
    }

    /** Y of the first pixel below the last visible row. */
    public int rowsBottom() {
        return rowY(visibleRows);
    }

    /** Y of the Save and Cancel buttons. */
    public int buttonY() {
        return buttonY;
    }

    public int saveX() {
        return saveX;
    }

    public int cancelX() {
        return cancelX;
    }

    /**
     * Which visible row a y coordinate lands on, or -1 for the title area, gaps below the rows and the
     * button strip.
     */
    public int rowAt(double mouseY) {
        if (visibleRows <= 0) {
            return -1;
        }
        double offset = mouseY - ROW_TOP;
        if (offset < 0.0D || offset >= visibleRows * ROW_HEIGHT) {
            return -1;
        }
        return (int) (offset / ROW_HEIGHT);
    }

    /** How many devices of a list of {@code count} cannot be shown at all. */
    public int hidden(int count) {
        return Math.max(0, count - visibleRows);
    }

    /** True when every device of a list of {@code count} has a row. */
    public boolean fits(int count) {
        return hidden(count) == 0;
    }

    /** True when the columns had to be narrowed to stay on screen. */
    public boolean narrow() {
        return columnWidth < COLUMN_WIDTH;
    }
}
