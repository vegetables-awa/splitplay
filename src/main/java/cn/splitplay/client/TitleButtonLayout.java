package cn.splitplay.client;

/**
 * Where the two title screen buttons sit.
 *
 * <p>The coordinates are GUI units, so the buttons keep the same place relative to the GUI area whatever
 * the window size or the GUI scale is. Minecraft rebuilds the screen widgets on resize, so these numbers
 * are recomputed every time the buttons are added.</p>
 */
public final class TitleButtonLayout {

    public static final int TWO_PLAYERS_WIDTH = 110;
    public static final int DEVICES_WIDTH = 90;
    public static final int BUTTON_HEIGHT = 20;
    /** Distance from the left and bottom edges, in GUI units. */
    public static final int MARGIN = 6;
    /** Space between the two buttons. */
    public static final int GAP = 4;

    public record Rect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }

    private TitleButtonLayout() {
    }

    public static Rect twoPlayers(int screenWidth, int screenHeight) {
        return new Rect(MARGIN, rowY(screenHeight), TWO_PLAYERS_WIDTH, BUTTON_HEIGHT);
    }

    /**
     * Right of the first button, but pulled left on a narrow window so it stays inside instead of being
     * clipped at the edge.
     */
    public static Rect devices(int screenWidth, int screenHeight) {
        int preferred = MARGIN + TWO_PLAYERS_WIDTH + GAP;
        int x = Math.min(preferred, screenWidth - DEVICES_WIDTH - MARGIN);
        return new Rect(Math.max(MARGIN, x), rowY(screenHeight), DEVICES_WIDTH, BUTTON_HEIGHT);
    }

    /**
     * Bottom margin when there is room for it, then clamped into the window: a GUI area shorter than a
     * button would otherwise put the row past the bottom edge.
     */
    private static int rowY(int screenHeight) {
        int preferred = screenHeight - BUTTON_HEIGHT - MARGIN;
        int highest = Math.max(0, screenHeight - BUTTON_HEIGHT);
        return Math.max(0, Math.min(preferred, highest));
    }
}
