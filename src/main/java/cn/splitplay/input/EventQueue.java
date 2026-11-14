package cn.splitplay.input;

import cn.splitplay.rawin.RawInputEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The hand-off between the raw input thread and the game thread.
 *
 * <p>Two things can go wrong here and both were real bugs waiting to happen:</p>
 *
 * <p>A 1000 Hz mouse produces far more events than the game consumes. They used to be queued one by one
 * and replayed one by one, which is only slow; since consecutive moves of the same mouse are additive,
 * they are merged instead, so a busy mouse costs one event per tick rather than a hundred.</p>
 *
 * <p>A stalled game thread (opening a world takes seconds) lets the queue grow without bound, and then
 * the whole backlog is replayed in a single tick: the pointer jumps and the character keeps walking for
 * a moment after the freeze. Past the capacity the queue is therefore thrown away, and the caller is
 * told so it can release every key: replaying half a second of old presses is worse than dropping them.</p>
 */
public final class EventQueue {

    /** What happened to an event that was handed over. */
    public enum Result {
        /** Queued as it came. */
        OFFERED,
        /** Merged into the move that was already waiting for the same device. */
        COALESCED,
        /** The queue was full: everything waiting was dropped, including this event. */
        OVERFLOWED
    }

    /** Enough for a few ticks of a busy keyboard, small enough that a stall cannot pile up seconds. */
    public static final int DEFAULT_CAPACITY = 512;

    private final int capacity;
    private final Deque<RawInputEvent> events = new ArrayDeque<>();
    private long discarded;

    public EventQueue() {
        this(DEFAULT_CAPACITY);
    }

    /** A capacity below one would make the queue useless, so it is clamped instead of trusted. */
    public EventQueue(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized Result offer(RawInputEvent event) {
        if (event == null || event.kind == null) {
            return Result.OFFERED;
        }
        if (coalesce(event)) {
            return Result.COALESCED;
        }
        if (events.size() >= capacity) {
            discarded = events.size();
            events.clear();
            return Result.OVERFLOWED;
        }
        events.addLast(event);
        return Result.OFFERED;
    }

    /**
     * Merges a move into the move that is already waiting, but only when that move is the last event and
     * belongs to the same device: merging across a click would let the pointer arrive before a press that
     * happened first.
     */
    private boolean coalesce(RawInputEvent event) {
        if (event.kind != RawInputEvent.Kind.MOVE || events.isEmpty()) {
            return false;
        }
        RawInputEvent last = events.peekLast();
        if (last.kind != RawInputEvent.Kind.MOVE || !sameDevice(last.devicePath, event.devicePath)) {
            return false;
        }
        events.removeLast();
        events.addLast(RawInputEvent.move(event.devicePath, last.dx + event.dx, last.dy + event.dy));
        return true;
    }

    private static boolean sameDevice(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    public synchronized RawInputEvent poll() {
        return events.pollFirst();
    }

    public synchronized int size() {
        return events.size();
    }

    /** How many events the last overflow threw away; zero when nothing was ever dropped. */
    public synchronized long discarded() {
        return discarded;
    }

    public synchronized void clear() {
        events.clear();
    }

    /**
     * Takes every mouse move out of the queue, leaving keys, buttons and scroll events in place.
     *
     * <p>Moves are drained once per rendered frame instead of once per tick: a tick runs 20 times a
     * second, so a cursor moved only from there jumps in 50 ms steps, which reads as "laggy". The
     * camera keeps its tick cadence, because vanilla paces that itself.</p>
     */
    public synchronized java.util.List<RawInputEvent> drainMoves() {
        if (events.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<RawInputEvent> moves = new java.util.ArrayList<>();
        java.util.ArrayDeque<RawInputEvent> rest = new java.util.ArrayDeque<>(events.size());
        for (RawInputEvent event : events) {
            if (event.kind == RawInputEvent.Kind.MOVE) {
                moves.add(event);
            } else {
                rest.add(event);
            }
        }
        if (moves.isEmpty()) {
            return java.util.List.of();
        }
        events.clear();
        events.addAll(rest);
        return moves;
    }

    /** Discards queued mouse movement without applying it; used while another window is in front. */
    public synchronized void dropMoves() {
        if (events.isEmpty()) {
            return;
        }
        java.util.ArrayDeque<RawInputEvent> rest = new java.util.ArrayDeque<>(events.size());
        for (RawInputEvent event : events) {
            if (event.kind != RawInputEvent.Kind.MOVE) {
                rest.add(event);
            }
        }
        events.clear();
        events.addAll(rest);
    }
}
