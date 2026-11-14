package cn.splitplay.launch;

import cn.splitplay.SplitPlay;
import com.sun.jna.platform.win32.WinDef.HWND;
import net.minecraft.client.Minecraft;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens the second window, puts both windows side by side and gets the taskbar out of the way.
 *
 * <p>Everything runs on daemon threads: the game keeps rendering while the second process boots,
 * and a crash here must never take the running window down with it.</p>
 */
public final class SplitLauncher {

    private static final long WINDOW_TIMEOUT_MS = 300_000L;
    private static final long POLL_MS = 400L;
    /** The game resizes its own window once the resource reload finishes, so re-apply for a while. */
    private static final long SETTLE_MS = 25_000L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    /** Left behind while the taskbar is hidden, so a crash can be cleaned up on the next start. */
    private static final String MARKER = ".splitplay-split";

    private SplitLauncher() {
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    /** Entry point from the title screen button. Returns immediately. */
    public static void startTwoPlayerMode() {
        if (SecondInstance.isChild()) {
            SplitPlay.LOGGER.info("splitplay: this window is already the second player");
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            SplitPlay.LOGGER.info("splitplay: second window is already starting");
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Path gameDir = minecraft.gameDirectory.toPath();
        long ownPid = ProcessHandle.current().pid();

        Thread worker = new Thread(() -> {
            try {
                NativeWindows.makeDpiAware();
                Process child = SecondInstance.start(gameDir, SecondInstance.defaultSecondName());
                long childPid = child.pid();
                SplitPlay.LOGGER.info("splitplay: second window pid {}", childPid);
                arrange(ownPid, child, childPid);
            } catch (Throwable error) {
                SplitPlay.LOGGER.error("splitplay: could not start the second window", error);
                NativeWindows.showTaskbar();
                RUNNING.set(false);
            }
        }, "splitplay-launch");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Read from the config so a wide monitor can put the windows left/right instead of
     * top/bottom: {@code "horizontal"} means side by side, anything else means stacked.
     */
    private static boolean splitSideBySide() {
        var config = cn.splitplay.SplitPlayClient.config();
        return config != null && "horizontal".equalsIgnoreCase(config.layout);
    }

    private static void arrange(long ownPid, Process child, long childPid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WINDOW_TIMEOUT_MS;
        Map<Long, HWND> windows = Map.of();
        while (System.currentTimeMillis() < deadline) {
            if (!child.isAlive() && windows.size() < 2) {
                SplitPlay.LOGGER.error("splitplay: the second window exited with code {}",
                        child.isAlive() ? 0 : child.exitValue());
                RUNNING.set(false);
                return;
            }
            windows = NativeWindows.findMainWindows(Set.of(ownPid, childPid));
            if (windows.containsKey(ownPid) && windows.containsKey(childPid)) {
                break;
            }
            Thread.sleep(POLL_MS);
        }

        HWND top = windows.get(ownPid);
        HWND bottom = windows.get(childPid);
        if (top == null || bottom == null) {
            SplitPlay.LOGGER.error("splitplay: gave up waiting for both game windows to appear");
            NativeWindows.showTaskbar();
            RUNNING.set(false);
            return;
        }

        int width = NativeWindows.screenWidth();
        int height = NativeWindows.screenHeight();
        boolean sideBySide = splitSideBySide();
        SplitLayout layout = SplitLayout.of(width, height, sideBySide);
        SplitLayout.Rect topRect = layout.first();
        SplitLayout.Rect bottomRect = layout.second();
        SplitPlay.LOGGER.info("splitplay: screen {}x{}, arranging {} and {} ({})", width, height,
                ownPid, childPid, sideBySide ? "side by side" : "one above the other");

        touchMarker();
        NativeWindows.hideTaskbar();
        int nudged = 0;
        int rediscovered = 0;
        long settleUntil = System.currentTimeMillis() + SETTLE_MS;
        while (System.currentTimeMillis() < settleUntil) {
            boolean topFailed = nudgeState(top, topRect) == NUDGE_FAILED;
            boolean bottomFailed = nudgeState(bottom, bottomRect) == NUDGE_FAILED;
            nudged++;
            if (topFailed || bottomFailed) {
                // A handle that no longer works means the game recreated its window (or a mod did). Keep
                // arranging the old one and the split silently stops being applied, so look them up again.
                Map<Long, HWND> found = NativeWindows.findMainWindows(Set.of(ownPid, childPid));
                HWND newTop = found.get(ownPid);
                HWND newBottom = found.get(childPid);
                if (newTop != null && newBottom != null) {
                    top = newTop;
                    bottom = newBottom;
                    rediscovered++;
                    SplitPlay.LOGGER.info("splitplay: re-discovered the game windows after a stale handle");
                } else {
                    SplitPlay.LOGGER.warn("splitplay: a window handle stopped working and the windows could"
                            + " not be found again");
                }
            }
            Thread.sleep(1000L);
        }
        // Leave the first player's window focused; the repeat passes above must not steal focus.
        NativeWindows.place(top, topRect.x(), topRect.y(), topRect.width(), topRect.height(), true);
        SplitPlay.LOGGER.info("splitplay: both windows arranged ({} pass(es), {} re-discovery), taskbar hidden",
                nudged, rediscovered);

        Thread watcher = new Thread(() -> {
            try {
                while (child.isAlive() && ProcessHandle.current().isAlive()) {
                    Thread.sleep(1000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                restoreTaskbar();
                SplitPlay.LOGGER.info("splitplay: a window closed, taskbar restored");
                RUNNING.set(false);
            }
        }, "splitplay-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    /**
     * Puts a window back where it belongs, but only when it actually drifted: the game resizes
     * itself once the resource reload finishes, and touching a window that is already correct
     * steals focus from a player who has started using it.
     */
    /** The window is already where it belongs. */
    private static final int NUDGE_CORRECT = 0;
    /** The window was moved. */
    private static final int NUDGE_PLACED = 1;
    /** The handle could not be used any more. */
    private static final int NUDGE_FAILED = -1;

    private static int nudgeState(HWND hwnd, SplitLayout.Rect target) {
        int[] current = NativeWindows.rectOf(hwnd);
        if (current != null && current[0] == target.x() && current[1] == target.y()
                && current[2] == target.width() && current[3] == target.height()) {
            return NUDGE_CORRECT;
        }
        return NativeWindows.place(hwnd, target.x(), target.y(), target.width(), target.height(), false)
                ? NUDGE_PLACED : NUDGE_FAILED;
    }

    /** Restores the taskbar if the game is closed while two windows are up. */
    public static void onShutdown() {
        // The pointer clip has to go back to the desktop even for the child window, or the user is
        // left with a pointer that cannot reach most of the screen.
        try {
            cn.splitplay.cursor.VirtualCursor.releasePointer();
        } catch (Throwable ignored) {
            // Best effort: the clip is released with the process anyway.
        }
        if (SecondInstance.isChild()) {
            return;
        }
        try {
            restoreTaskbar();
        } catch (Throwable error) {
            SplitPlay.LOGGER.warn("splitplay: could not restore the taskbar: {}", error.toString());
        }
    }

    /**
     * Puts the taskbar back if a previous run died while it was hidden: a killed process never runs
     * its shutdown hook, and a hidden taskbar looks like a broken desktop.
     */
    public static void recoverTaskbarIfNeeded(Path gameDir) {
        Path marker = gameDir == null ? null : gameDir.resolve(MARKER);
        boolean marked = marker != null && java.nio.file.Files.exists(marker);
        // A killed process leaves no marker behind, so the taskbar itself is the better clue:
        // nothing else on this machine hides it while the game is running.
        if (!marked && NativeWindows.isTaskbarVisible()) {
            return;
        }
        SplitPlay.LOGGER.info("splitplay: taskbar was left hidden (marker {}), restoring it", marked);
        restoreTaskbar();
    }

    private static void restoreTaskbar() {
        NativeWindows.showTaskbar();
        Path marker = Minecraft.getInstance() == null ? null
                : markerFile(Minecraft.getInstance().gameDirectory.toPath());
        if (marker != null) {
            try {
                java.nio.file.Files.deleteIfExists(marker);
            } catch (IOException ignored) {
                // Cosmetic only.
            }
        }
    }

    private static Path markerFile(Path gameDir) {
        return gameDir == null ? null : gameDir.resolve(MARKER);
    }

    private static void touchMarker() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        Path marker = markerFile(minecraft.gameDirectory.toPath());
        if (marker == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(marker, Long.toString(ProcessHandle.current().pid()));
        } catch (IOException ignored) {
            // Cosmetic only.
        }
    }

    static void logFailure(IOException error) {
        SplitPlay.LOGGER.error("splitplay: {}", error.toString());
    }
}
