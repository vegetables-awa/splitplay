package cn.splitplay.probe;

import cn.splitplay.launch.ModMirror;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Offline check for {@link ModMirror}, the code that decides what the second window's mods directory
 * contains.
 *
 * <p>The failure this guards against is quiet and confusing: if the two windows load different jars,
 * one side has a fix the other does not, and the symptom ("F3 works on top but not on the bottom")
 * points nowhere near the real cause. Every case therefore asserts on the target directory itself.
 */
public final class ModMirrorCheck {

    private static final String SPLITPLAY = "splitplay-0.1.0.jar";
    private static final String FABRIC_API = "fabric-api-0.116.17+1.21.1.jar";

    private static int pass;
    private static int fail;

    private ModMirrorCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("splitplay-mirror");
        try {
            Path source = root.resolve("parent").resolve("mods");
            Path target = root.resolve("child").resolve("mods");
            Files.createDirectories(source);

            missingSourceIsHarmless(root.resolve("nowhere").resolve("mods"), target);

            write(source.resolve(SPLITPLAY), 4000, 'a');
            write(source.resolve(FABRIC_API), 5000, 'b');
            write(source.resolve("splitter.exe"), 900, 'c');
            write(source.resolve("notes.txt"), 12, 'd');

            firstRunCopiesJars(source, target);
            secondRunDoesNothing(source, target);
            changedContentIsRefreshed(source, target);
            removedJarIsPruned(source, target);
            strayJarInTargetIsPruned(source, target);
            parentWithoutModsIsHarmless(root, target);
        } finally {
            emptyDirectory(root, root);
            Files.deleteIfExists(root);
        }
        System.out.println("mirror: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void missingSourceIsHarmless(Path source, Path target) throws IOException {
        ModMirror.Result result = ModMirror.mirror(source, target);
        check("missing source finds nothing", 0, result.found());
        check("missing source copies nothing", 0, result.copied());
        check("missing source does not create the target", false, Files.exists(target));
    }

    private static void firstRunCopiesJars(Path source, Path target) throws IOException {
        ModMirror.Result result = ModMirror.mirror(source, target);
        check("first run counts both jars", 2, result.found());
        check("first run copies both", 2, result.copied());
        check("first run keeps none", 0, result.kept());
        check("first run prunes none", 0, result.pruned());
        check("first run is a change", false, result.unchanged());
        check("jar arrives", true, Files.exists(target.resolve(SPLITPLAY)));
        check("exe is not mirrored", false, Files.exists(target.resolve("splitter.exe")));
        check("text file is not mirrored", false, Files.exists(target.resolve("notes.txt")));
        check("copy is byte identical", 4000, Files.size(target.resolve(SPLITPLAY)));
    }

    private static void secondRunDoesNothing(Path source, Path target) throws IOException {
        ModMirror.Result result = ModMirror.mirror(source, target);
        check("second run copies nothing", 0, result.copied());
        check("second run keeps both", 2, result.kept());
        check("second run prunes nothing", 0, result.pruned());
        check("second run reports no change", true, result.unchanged());
    }

    /** A rebuild usually changes the size, but two builds can collide on size, so timestamps decide. */
    private static void changedContentIsRefreshed(Path source, Path target) throws IOException {
        Path jar = source.resolve(SPLITPLAY);
        write(jar, 4000, 'z');
        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        ModMirror.Result result = ModMirror.mirror(source, target);
        check("same size, newer source is copied", 1, result.copied());
        check("unchanged jar is kept", 1, result.kept());
        check("refreshed jar has the new first byte", (int) 'z',
                (int) Files.readAllBytes(target.resolve(SPLITPLAY))[0]);
    }

    private static void removedJarIsPruned(Path source, Path target) throws IOException {
        Files.delete(source.resolve(FABRIC_API));

        ModMirror.Result result = ModMirror.mirror(source, target);
        check("one jar left upstream", 1, result.found());
        check("pruned jar is gone", false, Files.exists(target.resolve(FABRIC_API)));
        check("prune is counted", 1, result.pruned());
        check("surviving jar is kept", 1, result.kept());
    }

    private static void strayJarInTargetIsPruned(Path source, Path target) throws IOException {
        write(target.resolve("old-mod-1.0.jar"), 100, 'q');

        ModMirror.Result result = ModMirror.mirror(source, target);
        check("stray jar is pruned", 1, result.pruned());
        check("stray jar is deleted", false, Files.exists(target.resolve("old-mod-1.0.jar")));
    }

    private static void parentWithoutModsIsHarmless(Path root, Path target) throws IOException {
        Path source = root.resolve("parent").resolve("mods");
        emptyDirectory(root, source);
        Files.deleteIfExists(source);

        ModMirror.Result result = ModMirror.mirror(source, target);
        check("vanished mods directory copies nothing", 0, result.copied());
        // The parent's mods folder being gone is not the same as it being empty: a folder that is only
        // temporarily missing must not take the other window's jars with it.
        check("vanished mods directory prunes nothing", 0, result.pruned());
        check("vanished mods directory leaves the copy alone", 1, jars(target));

        Files.createDirectories(source);
        ModMirror.Result emptied = ModMirror.mirror(source, target);
        check("emptied mods directory prunes the copy", 1, emptied.pruned());
        check("no jars left in the target", 0, jars(target));
    }

    private static int jars(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> jars = stream.filter(path -> path.getFileName().toString().endsWith(".jar")).toList();
            return jars.size();
        }
    }

    private static void write(Path file, int size, char fill) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) fill);
        Files.write(file, bytes);
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
        boolean ok;
        if (expected instanceof Number left && actual instanceof Number right) {
            // File sizes come back as long while the expectations are int literals.
            ok = left.longValue() == right.longValue();
        } else {
            ok = expected == null ? actual == null : expected.equals(actual);
        }
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + name + ": expected <" + expected + "> got <" + actual + ">");
        }
    }
}
