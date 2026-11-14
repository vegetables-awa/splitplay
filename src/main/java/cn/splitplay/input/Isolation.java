package cn.splitplay.input;

import cn.splitplay.SplitPlayClient;

/**
 * Global gate for per-device input isolation.
 *
 * <p>When isolation is active every keyboard and mouse event that reaches the game through the
 * normal Windows path is discarded (see the mixins in {@code cn.splitplay.mixin}) and only events
 * replayed by {@link InputRouter} from the assigned device pass through. Replayed events are
 * dispatched by calling the vanilla handlers, which would otherwise be cancelled again, so a
 * re-entrancy flag is kept here.</p>
 */
public final class Isolation {

    private static boolean replaying;
    private static final java.util.concurrent.atomic.AtomicLong BLOCKED = new java.util.concurrent.atomic.AtomicLong();

    private Isolation() {
    }

    /**
     * True when this instance owns a keyboard and a mouse, the user asked for isolation, and both
     * devices are actually plugged in.
     *
     * <p>The last part is not politeness: with isolation on, every native event is discarded, so a
     * configured device that is missing would make the window deaf to its own keyboard. Falling back
     * to vanilla input keeps it usable.</p>
     */
    public static boolean isActive() {
        return SplitPlayClient.config() != null
                && SplitPlayClient.config().isolatesInput()
                && SplitPlayClient.assignedDevicesPresent();
    }

    /** True while a replayed event is being dispatched into vanilla code. */
    public static boolean isReplaying() {
        return replaying;
    }

    public static void beginReplay() {
        replaying = true;
    }

    public static void endReplay() {
        replaying = false;
    }

    /** Whether a native (non-replayed) event has to be thrown away. */
    public static boolean shouldCancelNative() {
        return !replaying && isActive();
    }

    /** Counts an event that was dropped, so the log can show that the filter is doing work. */
    public static void noteBlocked() {
        BLOCKED.incrementAndGet();
    }

    /** Returns how many events were dropped since the previous call. */
    public static long drainBlockedCount() {
        return BLOCKED.getAndSet(0L);
    }
}
