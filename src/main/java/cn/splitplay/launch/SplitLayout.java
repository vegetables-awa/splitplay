package cn.splitplay.launch;

/**
 * The two rectangles one screen is split into.
 *
 * <p>Kept as a plain value so the arithmetic can be checked without a window: an off-by-one here is
 * invisible in code review and shows up as a one pixel strip of desktop between the two players, or as
 * the second window sliding off the screen on an odd height.</p>
 */
public record SplitLayout(Rect first, Rect second) {

    /** A window placement in physical pixels. */
    public record Rect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }

    /**
     * Splits the screen in two.
     *
     * <p>The first rectangle takes the integer half and the second takes the remainder, so odd
     * dimensions are covered completely instead of leaving a one pixel gap. The second rectangle can
     * therefore be one pixel the larger of the two.</p>
     *
     * @param sideBySide left/right instead of top/bottom
     */
    public static SplitLayout of(int screenWidth, int screenHeight, boolean sideBySide) {
        int width = Math.max(1, screenWidth);
        int height = Math.max(1, screenHeight);
        if (sideBySide) {
            int firstWidth = width / 2;
            return new SplitLayout(
                    new Rect(0, 0, firstWidth, height),
                    new Rect(firstWidth, 0, width - firstWidth, height));
        }
        int firstHeight = height / 2;
        return new SplitLayout(
                new Rect(0, 0, width, firstHeight),
                new Rect(0, firstHeight, width, height - firstHeight));
    }

    /** True when the two rectangles tile the screen exactly: no gap, no overlap, no overhang. */
    public boolean tiles(int screenWidth, int screenHeight) {
        int covered = first.width() * first.height() + second.width() * second.height();
        boolean inside = first.x() >= 0 && first.y() >= 0 && second.x() >= 0 && second.y() >= 0
                && first.right() <= screenWidth && first.bottom() <= screenHeight
                && second.right() <= screenWidth && second.bottom() <= screenHeight;
        boolean overlaps = first.x() < second.right() && second.x() < first.right()
                && first.y() < second.bottom() && second.y() < first.bottom();
        return inside && !overlaps && covered == screenWidth * screenHeight;
    }
}
