package cn.splitplay.cursor;

/**
 * Shape and placement of the drawn pointer, kept apart from the drawing calls so it can be checked
 * without a running game: the arrow has to stay inside the window at every GUI scale, and an
 * off-by-one there means a pointer that disappears at an edge or paints over the wrong row.
 */
public final class CursorGeometry {

    /** True horizontal extent: the widest row reaches column 9, so the arrow is 10 pixels wide. */
    public static final int WIDTH = 10;

    public static final int HEIGHT = 16;

    /** Classic arrow outline, one entry per row: {left, right} inclusive, relative to the tip. */
    public static final int[][] ARROW = {
        {0, 1}, {0, 2}, {0, 3}, {0, 4}, {0, 5}, {0, 6}, {0, 7}, {0, 8},
        {0, 8}, {0, 7}, {0, 6}, {2, 6}, {2, 6}, {3, 7}, {4, 8}, {5, 9},
    };

    /** One pixel black border in eight directions. */
    public static final int[][] OUTLINE = {
        {-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1},
    };

    private CursorGeometry() {
    }

    /** Window pixels to GUI units. */
    public static int toGui(double pixel, double scale) {
        if (scale <= 0.0D) {
            return 0;
        }
        return (int) Math.round(pixel / scale);
    }

    /** Largest origin that still keeps the whole arrow inside {@code guiSize}. */
    public static int maxOrigin(int guiSize, int length) {
        return Math.max(0, guiSize - length - 1);
    }

    /** Draw origin for a tip position, clamped so the arrow never leaves the window. */
    public static int origin(double pixel, double scale, int length, int guiSize) {
        int value = toGui(pixel, scale);
        if (value < 0) {
            return 0;
        }
        return Math.min(value, maxOrigin(guiSize, length));
    }

    /** True when a box of {@code length} drawn at {@code origin} stays inside the window. */
    public static boolean inside(int origin, int length, int guiSize) {
        return origin >= 0 && origin + length <= guiSize;
    }
}
