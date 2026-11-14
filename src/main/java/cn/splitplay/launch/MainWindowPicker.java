package cn.splitplay.launch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the main window out of the windows a process owns.
 *
 * <p>A game process also creates helper windows (splash, invisible message windows, IME candidates), and
 * {@code EnumWindows} reports all of them. The framed main window is the big visible one with a title.
 * The rule is kept as a pure function because getting it wrong is not a crash: the arrangement silently
 * grabs the wrong window, or waits out the full five minute timeout, and the symptom ("nothing happened")
 * is impossible to trace back to a filter.</p>
 */
public final class MainWindowPicker {

    /** One window as found by an enumeration. */
    public record Candidate(long pid, long handle, String title, int width, int height, boolean visible) {
        public long area() {
            return (long) Math.max(0, width) * Math.max(0, height);
        }

        public boolean usable() {
            return visible && title != null && !title.isBlank() && area() > 0;
        }
    }

    private MainWindowPicker() {
    }

    /**
     * The largest usable window per process id, in encounter order.
     *
     * <p>Ties keep the first candidate: the enumeration order is stable per run, and picking the first
     * match makes the result reproducible instead of depending on a comparison detail.</p>
     */
    public static Map<Long, Long> pick(List<Candidate> candidates) {
        Map<Long, Long> handles = new LinkedHashMap<>();
        Map<Long, Long> areas = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (!candidate.usable()) {
                continue;
            }
            Long best = areas.get(candidate.pid());
            if (best == null || candidate.area() > best) {
                areas.put(candidate.pid(), candidate.area());
                handles.put(candidate.pid(), candidate.handle());
            }
        }
        return handles;
    }
}
