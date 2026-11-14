package cn.splitplay.input;

import cn.splitplay.SplitPlay;
import cn.splitplay.cursor.VirtualCursor;
import cn.splitplay.SplitPlayClient;
import cn.splitplay.rawin.RawInputEvent;
import cn.splitplay.mixin.KeyboardHandlerAccessor;
import cn.splitplay.mixin.MouseHandlerAccessor;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Feeds events of the assigned keyboard and mouse into vanilla code.
 *
 * <p>Events arrive on the raw input thread and are applied on the client thread once per tick,
 * so vanilla data structures are only touched from the thread that owns them. Replay goes through
 * the real {@code KeyboardHandler} / {@code MouseHandler} methods (via mixin invokers) instead of
 * re-implementing what they do: GUI clicks, attack/use, hotbar, chat typing and camera turning all
 * keep the behaviour of the vanilla paths.</p>
 */
public final class InputRouter {

    private static final EventQueue QUEUED = new EventQueue();

    /** Overflow is logged at most once a second: a long stall would otherwise write thousands of lines. */
    private static final long ONE_SECOND_NANOS = 1_000_000_000L;
    private static volatile long lastOverflowLogNanos;

    /** Keys and mouse buttons this window has replayed a press for and not yet released. */
    private static final HeldInput HELD = new HeldInput();

    private static double pendingDx;
    private static double pendingDy;
    private static double pendingScrollX;
    private static double pendingScrollY;

    /** Last reported isolation state, so the transition is logged once instead of every tick. */
    private static Boolean reportedActive;

    /** Tick counter for the throttled report of dropped native events. */
    private static int reportTicks;

    /** Movement applications and raw events, so a silent window can be told apart from a stuck one. */
    private static long replayed;
    private static volatile long rawSeen;
    /** Queued events drained in the last report window, split by the branch they took. */
    private static long drained;
    /** Turns logged verbatim, so the first few can be inspected without spamming the log. */
    private static int turnLogged;
    private static long worldMoves;
    private static long menuMoves;

    /** Set while this window is the one that turned "pause on lost focus" off. */
    private static boolean pauseOptionForced;

    /** True while replay is paused because the focus is in another application. */
    private static boolean parked;

    /** When a game window last had the focus; replay survives this long without one. */
    private static final long FOCUS_GRACE_NANOS = 500_000_000L;
    private static long lastGameFocusNanos = System.nanoTime();

    /** Last screen seen, used to notice the world <-> menu transition. */
    private static net.minecraft.client.gui.screens.Screen lastScreen;

    /**
     * Set when the stream of releases can no longer be trusted: a device was unplugged, or routing
     * was switched off while a key or button was still held. Vanilla would otherwise keep walking,
     * mining or shooting until the same key happens to be pressed and released again.
     */
    private static volatile boolean releaseRequested;

    private InputRouter() {
    }

    /** Asks the client thread to release everything vanilla currently thinks is held. */
    public static void requestRelease() {
        releaseRequested = true;
    }

    /**
     * Handed over from the raw input thread. A full queue means the game thread stalled: everything
     * waiting is dropped instead of being replayed late, and every held key is released, because half a
     * second of stale presses is worse than none.
     */
    public static void offer(RawInputEvent event) {
        if (QUEUED.offer(event) == EventQueue.Result.OVERFLOWED) {
            long now = System.nanoTime();
            if (now - lastOverflowLogNanos > ONE_SECOND_NANOS) {
                lastOverflowLogNanos = now;
                SplitPlay.LOGGER.warn("splitplay: the game thread fell behind, discarded {} queued input event(s)",
                        QUEUED.discarded());
            }
            requestRelease();
        }
    }

    /**
     * True while this window has replayed a press for {@code key} (a GLFW key code) without its
     * release. Used to answer {@code InputConstants.isKeyDown}, which vanilla consults for the
     * F3 debug combos and a few shortcuts; client thread only.
     */
    public static boolean isHeld(int key) {
        return HELD.isKeyHeld(key);
    }

    public static void clear() {
        QUEUED.clear();
        HELD.clear();
        pendingDx = 0.0D;
        pendingDy = 0.0D;
        pendingScrollX = 0.0D;
        pendingScrollY = 0.0D;
    }

    /** Called once per client tick from a mixin into {@code Minecraft.tick()}. */
    public static void tick(Minecraft minecraft) {
        if (!Isolation.isActive()) {
            if (Boolean.TRUE.equals(reportedActive)) {
                reportedActive = Boolean.FALSE;
                SplitPlay.LOGGER.info("splitplay: input isolation off, vanilla input is back");
                // Vanilla may still believe a key of ours is down; hand its keyboard back clean.
                releaseRequested = true;
            }
            long windowOff = minecraft.getWindow() == null ? 0L : minecraft.getWindow().getWindow();
            if (releaseRequested && windowOff != 0L) {
                releaseHeld(minecraft, windowOff);
            }
            clear();
            return;
        }

        if (!Boolean.TRUE.equals(reportedActive)) {
            reportedActive = Boolean.TRUE;
            SplitPlay.LOGGER.info("splitplay: input isolation on, replaying {} + {}",
                    SplitPlayClient.describe(SplitPlayClient.config().keyboard),
                    SplitPlayClient.describe(SplitPlayClient.config().mouse));
        }
        applyPauseOption(minecraft);

        long window = minecraft.getWindow().getWindow();
        if (window == 0L) {
            clear();
            return;
        }

        // Focus is in some other application: pause replay. Without this, moving the mouse in a
        // browser with the same device keeps turning this window's camera behind the browser, and
        // keys typed elsewhere arrive here too.
        //
        // A short grace period matters more than it looks: clicking a terminal for a second, or a
        // launcher flashing its window, used to stop both games dead and look like the mod had
        // frozen. Half a second of stale input is harmless; a frozen game is not.
        if (SplitPlayClient.anyGameWindowFocused()) {
            lastGameFocusNanos = System.nanoTime();
        } else if (System.nanoTime() - lastGameFocusNanos > FOCUS_GRACE_NANOS) {
            if (!parked) {
                parked = true;
                SplitPlay.LOGGER.info("splitplay: focus is in another application, replay paused until"
                        + " one of the game windows is focused again");
            }
            releaseRequested = true;
            releaseHeld(minecraft, window);
            clear();
            return;
        }
        if (parked) {
            parked = false;
            SplitPlay.LOGGER.info("splitplay: a game window has the focus again, replay resumes");
        }

        RawInputEvent event;
        while ((event = QUEUED.poll()) != null) {
            drained++;
            dispatch(minecraft, window, event);
        }

        // Releases come after the queue: an event that was queued before the device vanished still
        // really happened, so it is replayed, but nothing may stay held when this pass ends. Doing this
        // before the queue would release an empty state and then replay the stale press on top of it,
        // which is exactly the stuck key the request exists to prevent.
        if (releaseRequested) {
            releaseHeld(minecraft, window);
        }

        // Every five seconds, say what this window did with the input it saw. Two numbers answer the two
        // questions a silent window raises: are raw events still arriving at all ("sent"), and is anything
        // being handed to vanilla ("replayed"). A window with sent=0 has a broken raw input stream; a
        // window with sent>0 and replayed=0 is dropping everything on the way.
        if (++reportTicks >= 100) {
            reportTicks = 0;
            long blocked = Isolation.drainBlockedCount();
            long sent = rawSeen;
            long applied = replayed;
            String ignored = SplitPlayClient.drainIgnoredEvents();
            if (blocked > 0L || sent > 0L || !ignored.isEmpty()) {
                SplitPlay.LOGGER.info(
                        "splitplay: 5s: dropped {} native, sent {} raw, applied {} move(s), {},"
                                + " drained={} world-move={} menu-move={}{}",
                        blocked, sent, applied, describeState(minecraft),
                        drained, worldMoves, menuMoves,
                        ignored.isEmpty() ? "" : ", ignored " + ignored);
            }
            rawSeen = 0L;
            drained = 0L;
            worldMoves = 0L;
            menuMoves = 0L;
        }

        // A screen opening is the moment the pointer has to appear; start it in the middle so it is
        // never left outside the window where it would be invisible.
        if (minecraft.screen != lastScreen) {
            lastScreen = minecraft.screen;
            if (lastScreen != null) {
                VirtualCursor.recentre(minecraft);
                VirtualCursor.syncVanillaCursor(minecraft.mouseHandler);
            }
        }

        if (pendingDx != 0.0D || pendingDy != 0.0D) {
            flushPendingMove(minecraft);
        }

        if (pendingScrollX != 0.0D || pendingScrollY != 0.0D) {
            double sx = pendingScrollX;
            double sy = pendingScrollY;
            pendingScrollX = 0.0D;
            pendingScrollY = 0.0D;
            if (minecraft.screen != null) {
                ScreenMouse.scroll(minecraft, sx, sy);
                replayed++;
            } else {
                MouseHandlerAccessor mouse = (MouseHandlerAccessor) minecraft.mouseHandler;
                Isolation.beginReplay();
                try {
                    mouse.splitplay$onScroll(window, sx, sy);
                } finally {
                    Isolation.endReplay();
                }
            }
        }
    }

    /** Counts one raw event that was addressed to this window; called from the raw input thread. */
    public static void noteRawEvent() {
        rawSeen++;
    }

    /**
     * Applies queued movement. Called from {@code MouseHandler.handleAccumulatedMovement} at HEAD, the
     * moment vanilla is about to consume the accumulated delta, so the movement of this tick turns the
     * camera in this tick instead of the next one. Also called from the tick as a fallback for frames in
     * which that method never runs (a loading world, for example).
     */
    /**
     * Applies queued mouse movement to the virtual cursor, once per rendered frame.
     *
     * <p>Only in a screen: the camera is paced by vanilla's tick and stays there. Called from the
     * render loop because a 20 Hz cursor visibly steps, while the raw devices report at 125-1000 Hz.</p>
     */
    public static void pumpFrame(net.minecraft.client.Minecraft minecraft) {
        if (minecraft == null || !Isolation.isActive()) {
            return;
        }
        // Per-frame application runs outside the tick path, so it needs the same focus gate: while
        // another application is in front the queue keeps filling (raw input is global), and turning
        // the camera for a window nobody is looking at is exactly the "it moves on its own" report.
        if (!cn.splitplay.SplitPlayClient.anyGameWindowFocused()) {
            QUEUED.dropMoves();
            return;
        }
        java.util.List<RawInputEvent> moves = QUEUED.drainMoves();
        if (moves.isEmpty()) {
            return;
        }
        boolean inMenu = minecraft.screen != null;
        if (!inMenu && minecraft.player == null) {
            return;
        }
        for (RawInputEvent event : moves) {
            if (inMenu) {
                cn.splitplay.input.ScreenMouse.move(minecraft, event.dx, event.dy);
                menuMoves++;
            } else {
                turnCamera(minecraft, event.dx, event.dy);
                worldMoves++;
                replayed++;
            }
        }
    }

    public static void flushPendingMove(Minecraft minecraft) {
        double dx = pendingDx;
        double dy = pendingDy;
        pendingDx = 0.0D;
        pendingDy = 0.0D;
        if (minecraft == null || (dx == 0.0D && dy == 0.0D) || !Isolation.isActive()) {
            return;
        }
        long window = minecraft.getWindow().getWindow();
        if (window == 0L) {
            return;
        }
        if (minecraft.screen != null) {
            // Pointer mode: the virtual cursor moves and the screen hears about it directly. Vanilla's
            // own mouse path cannot do this job (single shared pointer position, press bookkeeping we
            // never fill in), so the screen's listener methods are called with our coordinates.
            ScreenMouse.move(minecraft, dx, dy);
            replayed++;
        }
        // World movement never lands here: it is turned into rotation as the events arrive, so the
        // camera does not wait for a tick.
    }

    /**
     * Turns off "pause on lost focus" while input is split. Without it, clicking the other window pauses
     * this one and opens its pause menu: the player is left looking at a menu, the camera no longer turns
     * and it reads as "my mouse stopped working".
     */
    private static void applyPauseOption(Minecraft minecraft) {
        if (minecraft.options == null) {
            return;
        }
        boolean active = SplitPlayClient.config() != null && SplitPlayClient.config().isolatesInput();
        if (active && minecraft.options.pauseOnLostFocus) {
            minecraft.options.pauseOnLostFocus = false;
            pauseOptionForced = true;
            SplitPlay.LOGGER.info("splitplay: pause on lost focus is off while input is split,"
                    + " otherwise the other window would pause this one");
        } else if (!active && pauseOptionForced) {
            // Only ever forced from true, so restoring means setting it back to true.
            minecraft.options.pauseOnLostFocus = true;
            pauseOptionForced = false;
        }
    }

    /**
     * Sends a release for everything vanilla still has down. Runs on the client thread, and only
     * once per request.
     */
    private static void releaseHeld(Minecraft minecraft, long window) {
        releaseRequested = false;
        int keys = HELD.heldKeyCount();
        int buttons = HELD.heldButtonCount();
        if (keys == 0 && buttons == 0) {
            return;
        }
        KeyboardHandlerAccessor keyboard = (KeyboardHandlerAccessor) minecraft.keyboardHandler;
        MouseHandlerAccessor mouse = (MouseHandlerAccessor) minecraft.mouseHandler;
        Isolation.beginReplay();
        try {
            for (int key : HELD.heldKeys()) {
                keyboard.splitplay$keyPress(window, key, 0, GLFW.GLFW_RELEASE, 0);
            }
            for (int button : HELD.heldButtons()) {
                mouse.splitplay$onPress(window, button, GLFW.GLFW_RELEASE, 0);
            }
        } finally {
            Isolation.endReplay();
        }
        HELD.clear();
        SplitPlay.LOGGER.info("splitplay: released {} key(s) and {} mouse button(s) that were still held",
                keys, buttons);
    }

    private static void replay(MouseHandlerAccessor mouse, long window, double x, double y) {
        Isolation.beginReplay();
        try {
            mouse.splitplay$onMove(window, x, y);
        } finally {
            Isolation.endReplay();
        }
    }

    /**
     * Turns the player by a raw mouse delta, using vanilla's own sensitivity curve.
     *
     * <p>Vanilla multiplies the accumulated pixel delta by {@code ((sensitivity * 0.6 + 0.2)^3) * 8}
     * before rotating, so the same formula keeps the feel identical. Doing the rotation here instead
     * of replaying {@code MouseHandler.onMove} removes a dependency that kept breaking: vanilla
     * accumulates {@code x - xpos} in absolute window pixels, and our replayed positions are not the
     * real pointer's, so a stale {@code xpos} could make every delta cancel out and the camera stand
     * still while the menu cursor (which uses its own coordinates) kept working.</p>
     */
    private static void turnCamera(Minecraft minecraft, double dx, double dy) {
        if (minecraft.options == null || minecraft.player == null) {
            return;
        }
        // The first few turns are logged verbatim: "the camera does not move" has been reported three
        // times and each time the answer was in here (null player, an exception, or deltas of zero).
        int logIndex = turnLogged < 5 ? ++turnLogged : 0;
        try {
            double base = minecraft.options.sensitivity().get() * 0.6D + 0.2D;
            // The config multiplier is applied on top of the vanilla curve, so 1.0 is exactly vanilla.
            double configured = SplitPlayClient.config() == null ? 1.0D : SplitPlayClient.config().sensitivity;
            double scale = base * base * base * 8.0D * configured;
            double invert = minecraft.options.invertYMouse().get() ? -1.0D : 1.0D;
            double turnX = dx * scale;
            double turnY = dy * scale * invert;
            // Vanilla feeds the tutorial the same values right before turning the player; skipping it
            // only breaks the tutorial prompts, which is why upstream does it too.
            if (minecraft.getTutorial() != null) {
                minecraft.getTutorial().onMouse(turnX, turnY);
            }
            minecraft.player.turn(turnX, turnY);
            if (logIndex > 0) {
                SplitPlay.LOGGER.info("splitplay: turned the camera by ({}, {}) from raw ({}, {})",
                        String.format(java.util.Locale.ROOT, "%.3f", turnX),
                        String.format(java.util.Locale.ROOT, "%.3f", turnY), dx, dy);
            }
        } catch (Throwable failure) {
            if (logIndex > 0) {
                SplitPlay.LOGGER.warn("splitplay: could not turn the camera", failure);
            }
        }
    }

    private static void dispatch(Minecraft minecraft, long window, RawInputEvent event) {
        switch (event.kind) {
            case KEY -> dispatchKey(minecraft, window, event);
            case BUTTON -> dispatchButton(minecraft, window, event);
            case MOVE -> {
                if (minecraft.screen == null && minecraft.player != null) {
                    // World: turn at the rate the events arrive. Queueing the delta for the next tick
                    // and relying on vanilla's accumulated movement meant the camera only reacted
                    // once per tick, and any mismatch in MouseHandler's absolute position made it do
                    // nothing at all.
                    turnCamera(minecraft, event.dx, event.dy);
                    worldMoves++;
                    replayed++;
                } else if (minecraft.screen != null) {
                    menuMoves++;
                    pendingDx += event.dx;
                    pendingDy += event.dy;
                }
                // screen == null && player == null is the tail of a world load; there is nobody to
                // turn and no cursor to move, so the delta is dropped instead of being queued as a
                // menu move that would never be flushed.
            }
            case WHEEL -> {
                pendingScrollX += event.dx;
                pendingScrollY += event.dy;
            }
        }
    }

    /**
     * What the router believes the game is doing, for the five-second line.
     *
     * <p>"The camera does not turn" has three very different causes and this is what tells them
     * apart without guessing: a screen is open (so moves go to the virtual cursor), the world is not
     * loaded yet, or the pointer mode is not the one the player expects.</p>
     */
    private static String describeState(Minecraft minecraft) {
        String screen = minecraft.screen == null ? "-" : minecraft.screen.getClass().getSimpleName();
        String world = minecraft.player == null ? "no-world" : "world";
        String cursor = "?";
        long window = minecraft.getWindow().getWindow();
        if (window != 0L) {
            int mode = org.lwjgl.glfw.GLFW.glfwGetInputMode(window, org.lwjgl.glfw.GLFW.GLFW_CURSOR);
            cursor = mode == org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED ? "locked"
                    : mode == org.lwjgl.glfw.GLFW.GLFW_CURSOR_HIDDEN ? "hidden" : "normal";
        }
        return "state=" + world + " screen=" + screen + " cursor=" + cursor
                + " clip=" + (cn.splitplay.launch.NativeWindows.clipActive() ? 1 : 0)
                + " cap=" + cn.splitplay.launch.NativeWindows.captureOwner()
                + " msgs=" + cn.splitplay.rawin.RawInputService.messagesSeen()
                + " out=" + cn.splitplay.rawin.RawInputService.eventsEmitted()
                + " active=" + (Isolation.isActive() ? 1 : 0)
                + " present=" + (SplitPlayClient.assignedDevicesPresent() ? 1 : 0)
                + " queued=" + QUEUED.size();
    }

    private static void dispatchKey(Minecraft minecraft, long window, RawInputEvent event) {
        int vk = event.vkey;
        if (vk == 0) {
            return;
        }
        int key = KeyMap.toGlfw(vk);
        if (key == GLFW.GLFW_KEY_UNKNOWN) {
            return;
        }

        boolean down = event.down;
        int action;
        if (down) {
            action = HELD.keyDown(key);
        } else {
            action = GLFW.GLFW_RELEASE;
            HELD.keyUp(key);
        }

        KeyboardHandlerAccessor keyboard = (KeyboardHandlerAccessor) minecraft.keyboardHandler;
        int modifiers = HELD.modifiers(key, down);

        Isolation.beginReplay();
        try {
            keyboard.splitplay$keyPress(window, key, event.scancode, action, modifiers);
            // Vanilla gets its characters from GLFW's char callback, which fires again for every key
            // repeat. Replaying only the first press would make a held key type one character and a
            // held backspace delete one letter, so repeats have to produce characters too.
            boolean typed = action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT;
            if (typed && minecraft.screen != null) {
                int codePoint = KeyChars.toChar(vk, (modifiers & GLFW.GLFW_MOD_SHIFT) != 0);
                if (codePoint > 0) {
                    keyboard.splitplay$charTyped(window, codePoint, modifiers);
                }
            }
        } finally {
            Isolation.endReplay();
        }
    }

    private static void dispatchButton(Minecraft minecraft, long window, RawInputEvent event) {
        int button = event.button;
        if (button < 0) {
            return;
        }

        boolean down = event.down;
        if (down) {
        if (HELD.buttonDown(button) == -1) {
                // A chatty device can report the same press twice; treating the second one as a new
                // click would toggle attack/use twice for one physical click.
                return;
            }
        } else {
        if (!HELD.buttonUp(button)) {
                return;
            }
        }

        if (minecraft.screen != null) {
            // Menus get the click at the virtual cursor's position, with the press bookkeeping screens
            // rely on (active button, press time) filled in so drags and sliders behave.
            ScreenMouse.button(minecraft, button, down);
            replayed++;
            return;
        }

        MouseHandlerAccessor mouse = (MouseHandlerAccessor) minecraft.mouseHandler;
        Isolation.beginReplay();
        try {
            mouse.splitplay$onPress(window, button, down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0);
        } finally {
            Isolation.endReplay();
        }
    }
}
