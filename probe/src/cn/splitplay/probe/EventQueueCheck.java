package cn.splitplay.probe;

import cn.splitplay.input.EventQueue;
import cn.splitplay.rawin.RawInputEvent;

/**
 * Offline check for the queue between the raw input thread and the game thread.
 *
 * <p>Two behaviours are load bearing. Consecutive moves of one mouse are merged, because a 1000 Hz mouse
 * would otherwise hand the game a hundred events per tick. And a full queue drops everything it holds,
 * because the alternative - replaying a backlog in a single tick after a stall - turns a two second world
 * load into the pointer jumping across the screen and the character walking on its own.</p>
 */
public final class EventQueueCheck {

    private static final String MOUSE = "hid#vid_30fa&pid_0400";
    private static final String OTHER_MOUSE = "hid#vid_10c4&pid_0005";
    private static final String KEYBOARD = "hid#vid_2a7a&pid_8a57";

    private static int pass;
    private static int fail;

    private EventQueueCheck() {
    }

    public static void main(String[] args) {
        basics();
        coalescing();
        noCrossCoalescing();
        overflow();
        capacity();
        concurrency();
        drainingMoves();
        System.out.println("eventqueue: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void basics() {
        EventQueue queue = new EventQueue();
        check("a new queue is empty", 0, queue.size());
        check("a new queue discarded nothing", 0L, queue.discarded());
        check("polling an empty queue gives nothing", null, queue.poll());
        check("an offered event is queued", EventQueue.Result.OFFERED,
                queue.offer(RawInputEvent.key(KEYBOARD, 0x41, 0x1E, true)));
        check("the queue holds it", 1, queue.size());
        RawInputEvent event = queue.poll();
        check("the event comes back", Boolean.TRUE, event != null);
        check("with its kind", RawInputEvent.Kind.KEY, event == null ? null : event.kind);
        check("with its device", KEYBOARD, event == null ? null : event.devicePath);
        check("with its key", 0x41, event == null ? null : event.vkey);
        check("the queue is empty again", 0, queue.size());
        check("a null event is ignored", EventQueue.Result.OFFERED, queue.offer(null));
        check("so the queue stays empty", 0, queue.size());
        queue.offer(RawInputEvent.key(KEYBOARD, 0x41, 0x1E, true));
        queue.clear();
        check("clear empties the queue", 0, queue.size());
        check("and polling gives nothing", null, queue.poll());
    }

    private static void coalescing() {
        EventQueue queue = new EventQueue();
        check("the first move is queued", EventQueue.Result.OFFERED,
                queue.offer(RawInputEvent.move(MOUSE, 3, 4)));
        check("a second move of the same mouse is merged", EventQueue.Result.COALESCED,
                queue.offer(RawInputEvent.move(MOUSE, 1, -2)));
        check("a third move is merged too", EventQueue.Result.COALESCED,
                queue.offer(RawInputEvent.move(MOUSE, 2, 2)));
        check("three moves became one event", 1, queue.size());
        RawInputEvent merged = queue.poll();
        check("the merged move keeps the device", MOUSE, merged == null ? null : merged.devicePath);
        check("the merged move sums dx", 6, merged == null ? 0 : merged.dx);
        check("the merged move sums dy", 4, merged == null ? 0 : merged.dy);
        check("the merged move is still a move", RawInputEvent.Kind.MOVE, merged == null ? null : merged.kind);
        check("the queue is empty again", 0, queue.size());
    }

    private static void noCrossCoalescing() {
        EventQueue queue = new EventQueue();
        queue.offer(RawInputEvent.move(MOUSE, 5, 5));
        check("a move of another mouse is not merged", EventQueue.Result.OFFERED,
                queue.offer(RawInputEvent.move(OTHER_MOUSE, 1, 1)));
        check("both mice keep their own move", 2, queue.size());

        EventQueue acrossClick = new EventQueue();
        acrossClick.offer(RawInputEvent.move(MOUSE, 1, 1));
        acrossClick.offer(RawInputEvent.button(MOUSE, 0, true));
        check("a move after a click is not merged with the one before it", EventQueue.Result.OFFERED,
                acrossClick.offer(RawInputEvent.move(MOUSE, 1, 1)));
        check("so all three events stay in order", 3, acrossClick.size());
        check("the order is move, click, move", RawInputEvent.Kind.MOVE, acrossClick.poll().kind);
        check("then the click", RawInputEvent.Kind.BUTTON, acrossClick.poll().kind);
        check("then the move", RawInputEvent.Kind.MOVE, acrossClick.poll().kind);

        EventQueue keys = new EventQueue();
        keys.offer(RawInputEvent.key(KEYBOARD, 0x57, 0x11, true));
        check("a repeated key press is not merged", EventQueue.Result.OFFERED,
                keys.offer(RawInputEvent.key(KEYBOARD, 0x57, 0x11, true)));
        check("both presses stay", 2, keys.size());
    }

    private static void overflow() {
        EventQueue queue = new EventQueue(4);
        queue.offer(RawInputEvent.key(KEYBOARD, 0x57, 0x11, true));
        queue.offer(RawInputEvent.key(KEYBOARD, 0x41, 0x1E, true));
        queue.offer(RawInputEvent.button(MOUSE, 0, true));
        check("four events fit", EventQueue.Result.OFFERED,
                queue.offer(RawInputEvent.key(KEYBOARD, 0x53, 0x1F, true)));
        check("the queue is full", 4, queue.size());
        check("the fifth event overflows", EventQueue.Result.OVERFLOWED,
                queue.offer(RawInputEvent.key(KEYBOARD, 0x44, 0x20, true)));
        check("overflow drops everything that was waiting", 0, queue.size());
        check("and says how much went missing", 4L, queue.discarded());
        check("polling after an overflow gives nothing", null, queue.poll());
        check("the queue still works afterwards", EventQueue.Result.OFFERED,
                queue.offer(RawInputEvent.key(KEYBOARD, 0x45, 0x12, true)));
        check("with the new event queued", 1, queue.size());
        check("the discarded count stays as the last overflow", 4L, queue.discarded());
    }

    private static void capacity() {
        EventQueue clamped = new EventQueue(0);
        check("a capacity of zero is clamped to one", EventQueue.Result.OFFERED,
                clamped.offer(RawInputEvent.button(MOUSE, 0, true)));
        check("one button fills it", EventQueue.Result.OVERFLOWED,
                clamped.offer(RawInputEvent.button(MOUSE, 0, false)));
        check("and the press is gone", 0, clamped.size());

        EventQueue negative = new EventQueue(-5);
        negative.offer(RawInputEvent.button(MOUSE, 1, true));
        check("a negative capacity is clamped too", EventQueue.Result.OVERFLOWED,
                negative.offer(RawInputEvent.button(MOUSE, 1, false)));

        check("the default capacity is generous but bounded", 512, EventQueue.DEFAULT_CAPACITY);
    }

    /**
     * The queue is written by the raw input thread while the game thread drains it, so it has to survive
     * both without throwing; the size may never exceed the capacity either way.
     */
    private static void concurrency() {
        EventQueue queue = new EventQueue(64);
        Throwable[] failure = new Throwable[1];
        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < 20_000; i++) {
                    queue.offer(RawInputEvent.move(MOUSE, 1, 0));
                    queue.offer(RawInputEvent.key(KEYBOARD, 0x41 + (i % 26), 0x1E, (i & 1) == 0));
                }
            } catch (Throwable error) {
                failure[0] = error;
            }
        }, "producer");
        Thread consumer = new Thread(() -> {
            try {
                for (int i = 0; i < 20_000; i++) {
                    queue.poll();
                }
            } catch (Throwable error) {
                failure[0] = error;
            }
        }, "consumer");
        producer.start();
        consumer.start();
        try {
            producer.join(20_000L);
            consumer.join(20_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        check("producer and consumer never threw", null, failure[0]);
        check("the queue never grew past its capacity", Boolean.TRUE, queue.size() <= 64);
        check("both threads finished", Boolean.FALSE, producer.isAlive() || consumer.isAlive());
    }

    private static void drainingMoves() {
        EventQueue queue = new EventQueue(8);
        queue.offer(RawInputEvent.move("dev", 1, 2));
        queue.offer(RawInputEvent.key("dev", 0x41, 0x1e, true));
        queue.offer(RawInputEvent.move("dev", 3, 4));
        queue.offer(RawInputEvent.button("dev", 0, true));
        var moves = queue.drainMoves();
        check("both moves came out", 2, moves.size());
        check("first move kept its delta", 1, moves.get(0).dx);
        check("second move came out in order", 3, moves.get(1).dx);
        check("the key stayed queued", 2, queue.size());
        check("the key is still first", RawInputEvent.Kind.KEY, queue.poll().kind);
        check("the button stayed queued", RawInputEvent.Kind.BUTTON, queue.poll().kind);

        queue.offer(RawInputEvent.move("dev", 5, 6));
        queue.dropMoves();
        check("dropMoves removes movement", 0, queue.size());

        check("draining an empty queue is empty", 0, queue.drainMoves().size());
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
