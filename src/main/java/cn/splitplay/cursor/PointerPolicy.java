package cn.splitplay.cursor;

/**
 * What should happen to the single system pointer while this window is running.
 *
 * <p>The reason this is a decision table rather than a few ifs: two windows share one pointer, and
 * every combination of "isolation on/off", "this window focused or not" and "in a menu or in the
 * world" needs a different treatment. Getting one of them wrong is exactly how the pointer ends up
 * moving under another application or clicking on something behind the game.</p>
 *
 * <ul>
 *   <li>{@code trap} - the pointer is confined to this window and re-centred every frame, so the
 *       physical mouse cannot move it anywhere else.</li>
 *   <li>{@code hide} - the game draws its own arrow, so the real one is in the way.</li>
 *   <li>{@code grabbedInWorld} - vanilla gates camera turning and continuous attack on
 *       {@code MouseHandler.isMouseGrabbed()}; without it the camera does not turn at all.</li>
 * </ul>
 */
public record PointerPolicy(boolean trap, boolean hide, boolean grabbedInWorld) {

    private static final PointerPolicy RELEASED = new PointerPolicy(false, false, false);
    private static final PointerPolicy PARKED_WORLD = new PointerPolicy(false, false, true);
    private static final PointerPolicy PARKED_MENU = new PointerPolicy(false, false, false);
    private static final PointerPolicy MENU = new PointerPolicy(true, true, false);
    private static final PointerPolicy WORLD = new PointerPolicy(true, true, true);

    /**
     * @param active  input is split and this window's devices are present
     * @param focused this window has the system focus
     * @param inMenu  a screen is open, so the player needs a pointer instead of a camera
     */
    public static PointerPolicy decide(boolean active, boolean focused, boolean inMenu) {
        if (!active) {
            // Vanilla owns everything: leave the pointer alone, including any grab state.
            return RELEASED;
        }
        if (!focused) {
            // Parked: the player keeps playing in this window, but the pointer belongs to whoever
            // has the focus. Touching it here is what made two windows fight over it.
            return inMenu ? PARKED_MENU : PARKED_WORLD;
        }
        return inMenu ? MENU : WORLD;
    }

    /** True when the system pointer has to be confined to this window's client area. */
    public boolean clipsPointer() {
        return trap;
    }
}
