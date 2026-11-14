package cn.splitplay.probe;

import cn.splitplay.util.ClaimStore;
import cn.splitplay.util.DevicePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Offline check for {@link ClaimStore}, the only piece of the mod with state on disk.
 *
 * <p>The last bug in this area was subtle: claims were written with the raw {@code \\?\} path but
 * looked up in normalised form, so the grey-out silently did nothing. Every case below therefore
 * asserts on the lookup side, not just on what was written.
 */
public final class ClaimCheck {

    private static final String KEYBOARD_A =
            "\\\\?\\HID#VID_2A7A&PID_8A57&MI_00#7&1288512d&0&0000#{884b96c3-56ef-11d1-bc8c-00a0c91405dd}";
    private static final String MOUSE_A =
            "\\\\?\\HID#VID_30FA&PID_0400#6&13506808&0&0000#{378de44c-56ef-11d1-bc8c-00a0c91405dd}";
    private static final String KEYBOARD_B =
            "\\\\?\\HID#VID_1C4F&PID_0002&MI_00#7&b7ed1e6&0&0000#{884b96c3-56ef-11d1-bc8c-00a0c91405dd}";

    private static int pass;
    private static int fail;

    private ClaimCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("splitplay-claims");
        try {
            Path directory = root.resolve(ClaimStore.DIR_NAME);
            emptyDirectory(root, directory);
            writesAndReadsBack(directory);
            stalePidIsPruned(directory);
            ownClaimIsIgnored(directory);
            otherWindowTakesOneDeviceEach(directory);
            brokenFileIsSkipped(directory);
            emptyClaimClearsFile(directory);
            directoryResolution(root);
        } finally {
            emptyDirectory(root, root);
        }
        System.out.println("claims: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void writesAndReadsBack(Path directory) {
        emptyDirectory(directory.getParent(), directory);
        ClaimStore.Claim claim = new ClaimStore.Claim(4242L, "P1", KEYBOARD_A, MOUSE_A);
        check("write succeeds", true, ClaimStore.write(directory, claim));
        check("file is named after the pid", true, Files.isRegularFile(ClaimStore.fileOf(directory, 4242L)));
        check("one file written", 1, ClaimStore.readAll(directory).size());

        ClaimStore.Claim read = ClaimStore.readAll(directory).get(0);
        check("pid survives", 4242L, read.pid);
        check("role survives", "P1", read.role);
        check("keyboard survives", KEYBOARD_A, read.keyboard);
        check("mouse survives", MOUSE_A, read.mouse);
        check("claim is not empty", false, read.isEmpty());
        check("empty claim with no devices", true, new ClaimStore.Claim(7L, "P2", "", "").isEmpty());
    }

    private static void stalePidIsPruned(Path directory) {
        emptyDirectory(directory.getParent(), directory);
        ClaimStore.write(directory, new ClaimStore.Claim(900001L, "P1", KEYBOARD_A, MOUSE_A));
        Map<String, String> taken = ClaimStore.claimedElsewhere(directory, 1L, pid -> false);
        check("dead pid claims nothing", 0, taken.size());
        check("dead pid file removed", false, Files.exists(ClaimStore.fileOf(directory, 900001L)));
    }

    private static void ownClaimIsIgnored(Path directory) {
        emptyDirectory(directory.getParent(), directory);
        ClaimStore.write(directory, new ClaimStore.Claim(55L, "P1", KEYBOARD_A, MOUSE_A));
        Map<String, String> taken = ClaimStore.claimedElsewhere(directory, 55L, pid -> true);
        check("own claim is not reported as taken", 0, taken.size());
        check("own claim file is kept", true, Files.exists(ClaimStore.fileOf(directory, 55L)));
    }

    private static void otherWindowTakesOneDeviceEach(Path directory) {
        emptyDirectory(directory.getParent(), directory);
        ClaimStore.write(directory, new ClaimStore.Claim(100L, "P1", KEYBOARD_A, MOUSE_A));
        ClaimStore.write(directory, new ClaimStore.Claim(200L, "P2", KEYBOARD_B, ""));

        Map<String, String> seenByP2 = ClaimStore.claimedElsewhere(directory, 200L, pid -> true);
        check("P2 sees P1's keyboard", "P1", seenByP2.get(DevicePath.normalise(KEYBOARD_A)));
        check("P2 sees P1's mouse", "P1", seenByP2.get(DevicePath.normalise(MOUSE_A)));
        check("P2 does not see its own keyboard", false,
                seenByP2.containsKey(DevicePath.normalise(KEYBOARD_B)));
        check("P2 sees two devices", 2, seenByP2.size());

        Map<String, String> seenByP1 = ClaimStore.claimedElsewhere(directory, 100L, pid -> true);
        check("P1 sees P2's keyboard", "P2", seenByP1.get(DevicePath.normalise(KEYBOARD_B)));
        check("P1 sees one device", 1, seenByP1.size());

        // Lookup goes through normalisation, so a prefix/case difference must still hit.
        check("prefix path still matches", "P1",
                seenByP2.get(DevicePath.normalise(KEYBOARD_A.replace("\\\\?\\", "").toUpperCase(java.util.Locale.ROOT))));
    }

    private static void brokenFileIsSkipped(Path directory) throws IOException {
        emptyDirectory(directory.getParent(), directory);
        ClaimStore.write(directory, new ClaimStore.Claim(300L, "P1", KEYBOARD_A, MOUSE_A));
        Files.writeString(ClaimStore.fileOf(directory, 301L), "{ this is not json", StandardCharsets.UTF_8);
        Files.writeString(ClaimStore.fileOf(directory, 302L), "", StandardCharsets.UTF_8);
        Files.writeString(ClaimStore.fileOf(directory, 303L), "{}", StandardCharsets.UTF_8);

        check("good claim still readable", 1, ClaimStore.readAll(directory).size());
        Map<String, String> taken = ClaimStore.claimedElsewhere(directory, 1L, pid -> true);
        check("broken files do not break the lookup", 2, taken.size());
        check("broken files are left alone", true, Files.exists(ClaimStore.fileOf(directory, 301L)));
    }

    private static void emptyClaimClearsFile(Path directory) {
        emptyDirectory(directory.getParent(), directory);
        ClaimStore.write(directory, new ClaimStore.Claim(400L, "P1", KEYBOARD_A, MOUSE_A));
        ClaimStore.remove(directory, 400L);
        check("remove deletes the file", false, Files.exists(ClaimStore.fileOf(directory, 400L)));
        check("remove is idempotent", true, ClaimStore.readAll(directory).isEmpty());
        check("remove on a missing directory is harmless", true, doQuietly(
                () -> ClaimStore.remove(directory.resolve("nope"), 1L)));
    }

    private static void directoryResolution(Path root) throws IOException {
        Path versions = root.resolve("versions");
        Files.createDirectories(versions);
        Path instance = versions.resolve("1.21.1-Fabric-SP");
        Path child = instance.resolve("splitplay-p2");
        Files.createDirectories(child);

        check("parent window finds the launcher root", root.resolve(ClaimStore.DIR_NAME),
                ClaimStore.directory(instance));
        check("second window agrees on the same directory", root.resolve(ClaimStore.DIR_NAME),
                ClaimStore.directory(child));
        check("null game dir has no claims", null, ClaimStore.directory(null));

        Path standalone = Files.createTempDirectory("splitplay-standalone");
        try {
            check("install without versions/ falls back locally", standalone.resolve(ClaimStore.DIR_NAME),
                    ClaimStore.directory(standalone));
            check("a nested folder still resolves to the launcher root", root.resolve(ClaimStore.DIR_NAME),
                    ClaimStore.directory(root.resolve("versions").resolve("1.21.1").resolve("nested")));
        } finally {
            emptyDirectory(standalone, standalone);
            Files.deleteIfExists(standalone);
        }
    }

    private static boolean doQuietly(Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static void emptyDirectory(Path root, Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(root))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // best effort in a temp directory
                        }
                    });
        } catch (IOException ignored) {
            // best effort in a temp directory
        }
    }

    private static void check(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + name + ": expected <" + expected + "> got <" + actual + ">");
        }
    }
}
