package cn.splitplay.client;

import cn.splitplay.SplitPlay;
import cn.splitplay.config.SplitConfig;
import cn.splitplay.util.ClaimStore;
import java.nio.file.Path;
import java.util.Map;

/**
 * Records which devices a running window has taken, so the other window can see them.
 *
 * <p>Thin wrapper over {@link ClaimStore}: this class only adds logging and the real "is that process
 * still running" answer, so the file handling itself stays testable without a game.</p>
 */
public final class DeviceClaims {

    private DeviceClaims() {
    }

    /** The shared directory, see {@link ClaimStore#directory(Path)}. */
    public static Path directory(Path gameDir) {
        return ClaimStore.directory(gameDir);
    }

    /** Publishes this window's claim, or removes it when the window takes no devices. */
    public static void publish(Path gameDir, SplitConfig config) {
        Path directory = directory(gameDir);
        if (directory == null) {
            return;
        }
        long pid = ProcessHandle.current().pid();
        if (config == null || !config.isolatesInput()) {
            ClaimStore.remove(directory, pid);
            return;
        }
        ClaimStore.Claim claim = new ClaimStore.Claim(pid, config.role, config.keyboard, config.mouse);
        if (!ClaimStore.write(directory, claim)) {
            SplitPlay.LOGGER.warn("splitplay: could not write {}", ClaimStore.fileOf(directory, pid));
            return;
        }
        for (ClaimStore.Claim other : ClaimStore.readAll(directory)) {
            if (other.pid == pid || other.isEmpty()) {
                continue;
            }
            // A window that crashed or was killed leaves its claim behind, and Windows reuses process
            // ids, so "the pid is alive" alone would keep those devices blocked. Prune as we go.
            if (!isAlive(other.pid)) {
                ClaimStore.remove(directory, other.pid);
                continue;
            }
            SplitPlay.LOGGER.info("splitplay: window '{}' (pid {}) already uses {}",
                    other.role, other.pid, other.keyboard);
        }
    }

    /** Devices claimed by other windows that are still running: raw-input path -> role. */
    public static Map<String, String> claimedElsewhere(Path gameDir) {
        Path directory = directory(gameDir);
        if (directory == null) {
            SplitPlay.LOGGER.info("splitplay: device claims unavailable, no game directory");
            return Map.of();
        }
        long own = ProcessHandle.current().pid();
        Map<String, String> taken = ClaimStore.claimedElsewhere(directory, own, DeviceClaims::isAlive);
        SplitPlay.LOGGER.info("splitplay: {} device(s) taken in {}: {}", taken.size(), directory, taken.keySet());
        return taken;
    }

    /** Device paths are compared case-insensitively and without the {@code \\?\} prefix. */
    public static String normalise(String path) {
        return cn.splitplay.util.DevicePath.normalise(path);
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
