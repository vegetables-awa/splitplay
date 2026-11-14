package cn.splitplay.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * The on-disk half of device claims, kept free of game classes so it can be tested without a
 * running Minecraft.
 *
 * <p>Two windows cannot talk to each other, but they do share a disk. Each window drops a small file
 * named after its process id into {@code <game>/splitplay-devices/}; a window that has no devices
 * assigned yet reads those files, drops the ones whose process is gone, and greys out the devices
 * somebody else is already using.</p>
 *
 * <p>Everything here is best effort: a half written or hand edited file must never stop the game, so
 * unreadable files are skipped instead of throwing.</p>
 */
public final class ClaimStore {

    public static final String DIR_NAME = "splitplay-devices";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private ClaimStore() {
    }

    /** One claim file. Kept tiny and readable: it is meant to be deleted by hand if ever stuck. */
    public static final class Claim {
        public long pid;
        public String role = "";
        public String keyboard = "";
        public String mouse = "";

        public Claim() {
        }

        public Claim(long pid, String role, String keyboard, String mouse) {
            this.pid = pid;
            this.role = role == null ? "" : role;
            this.keyboard = keyboard == null ? "" : keyboard;
            this.mouse = mouse == null ? "" : mouse;
        }

        /** True when this claim reserves nothing, i.e. the window is still vanilla. */
        public boolean isEmpty() {
            return (keyboard == null || keyboard.isEmpty()) && (mouse == null || mouse.isEmpty());
        }
    }

    /**
     * The shared directory. Both windows sit below the same launcher root, so the nearest ancestor
     * that has a {@code versions} folder is the place they agree on. Falls back to the game directory
     * itself, which keeps the mod usable with a hand made install.
     */
    public static Path directory(Path gameDir) {
        if (gameDir == null) {
            return null;
        }
        for (Path candidate = gameDir; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("versions"))) {
                return candidate.resolve(DIR_NAME);
            }
        }
        return gameDir.resolve(DIR_NAME);
    }

    public static Path fileOf(Path directory, long pid) {
        return directory.resolve(pid + ".json");
    }

    /** Writes the claim, creating the directory. Returns false when the write failed. */
    public static boolean write(Path directory, Claim claim) {
        if (directory == null || claim == null) {
            return false;
        }
        try {
            Files.createDirectories(directory);
            try (Writer writer = Files.newBufferedWriter(fileOf(directory, claim.pid), StandardCharsets.UTF_8)) {
                GSON.toJson(claim, writer);
            }
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    public static void remove(Path directory, long pid) {
        if (directory == null) {
            return;
        }
        try {
            Files.deleteIfExists(fileOf(directory, pid));
        } catch (IOException ignored) {
            // Cosmetic only: a stale file is pruned on the next read anyway.
        }
    }

    /** Every readable claim file, newest content first is not guaranteed; unreadable ones are skipped. */
    public static List<Claim> readAll(Path directory) {
        List<Claim> claims = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return claims;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    Claim claim = GSON.fromJson(reader, Claim.class);
                    if (claim != null && claim.pid > 0L) {
                        claims.add(claim);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // A half written or hand edited file must never stop the game.
                }
            }
        } catch (IOException ignored) {
            // No claims readable: treat every device as free.
        }
        return claims;
    }

    /**
     * Devices claimed by other windows that are still running: normalised raw-input path -> role.
     *
     * @param alive decides whether a pid is still running; injected so the check can fake it
     */
    public static Map<String, String> claimedElsewhere(Path directory, long ownPid, LongPredicate alive) {
        Map<String, String> taken = new HashMap<>();
        for (Claim claim : readAll(directory)) {
            if (claim.pid == ownPid) {
                continue;
            }
            if (alive != null && !alive.test(claim.pid)) {
                remove(directory, claim.pid);
                continue;
            }
            String role = claim.role == null || claim.role.isEmpty() ? "pid " + claim.pid : claim.role;
            if (claim.keyboard != null && !claim.keyboard.isEmpty()) {
                taken.put(DevicePath.normalise(claim.keyboard), role);
            }
            if (claim.mouse != null && !claim.mouse.isEmpty()) {
                taken.put(DevicePath.normalise(claim.mouse), role);
            }
        }
        return taken;
    }
}
