package cn.splitplay.launch;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the second window's {@code mods} directory in step with the parent's.
 *
 * <p>Both windows are separate game processes with separate game directories, so the second one
 * discovers mods on its own. It has to see exactly the same jars as the first: a window that runs one
 * version of this mod and a window that runs another is the kind of confusing half-broken state that
 * is very hard to diagnose from the symptom ("one side ignores F3"). A copy is used instead of a
 * junction because creating a junction needs a helper process, and starting a process is the one
 * operation that has been seen to hang inside a running game.</p>
 *
 * <p>Files are kept untouched when the name, size and timestamp all agree, so a second launch does no
 * work. Sizes are only a cheap shortcut, never the whole check: two different builds can share a
 * size.</p>
 */
public final class ModMirror {

    /** What {@link #mirror(Path, Path)} did, for the log line. */
    public record Result(int found, int copied, int kept, int pruned) {
        public boolean unchanged() {
            return copied == 0 && pruned == 0;
        }
    }

    private ModMirror() {
    }

    /**
     * Copies every {@code *.jar} from {@code sourceMods} into {@code targetMods}, refreshes jars that
     * changed and deletes jars that no longer exist upstream.
     *
     * @return how many jars were found, copied, left alone and pruned
     */
    public static Result mirror(Path sourceMods, Path targetMods) throws IOException {
        if (!Files.isDirectory(sourceMods)) {
            return new Result(0, 0, 0, 0);
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sourceMods, "*.jar")) {
            for (Path jar : stream) {
                jars.add(jar);
            }
        }
        Files.createDirectories(targetMods);
        int copied = 0;
        int kept = 0;
        Set<String> names = new LinkedHashSet<>();
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            names.add(name);
            Path destination = targetMods.resolve(name);
            if (isUpToDate(jar, destination)) {
                kept++;
                continue;
            }
            Files.copy(jar, destination, StandardCopyOption.REPLACE_EXISTING);
            copied++;
        }
        return new Result(jars.size(), copied, kept, prune(targetMods, names));
    }

    /**
     * Whether the copy is at least as new as the source and the same size.
     *
     * <p>{@link #mirror} never copies attributes, so a fresh copy is stamped with the current time and
     * stays "up to date" until the source changes again.</p>
     */
    private static boolean isUpToDate(Path source, Path destination) throws IOException {
        if (!Files.exists(destination) || Files.size(destination) != Files.size(source)) {
            return false;
        }
        return Files.getLastModifiedTime(destination).compareTo(Files.getLastModifiedTime(source)) >= 0;
    }

    /** Removes jars the parent no longer has, so both windows load the same set. */
    private static int prune(Path targetMods, Set<String> keep) throws IOException {
        int removed = 0;
        List<Path> stale = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetMods, "*.jar")) {
            for (Path jar : stream) {
                if (!keep.contains(jar.getFileName().toString())) {
                    stale.add(jar);
                }
            }
        }
        for (Path jar : stale) {
            Files.deleteIfExists(jar);
            removed++;
        }
        return removed;
    }
}
