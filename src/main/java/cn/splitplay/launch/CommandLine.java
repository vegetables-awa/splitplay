package cn.splitplay.launch;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure command line handling for the second window: splitting a command line into tokens,
 * substituting the per player values, and the offline uuid scheme.
 *
 * <p>Kept apart from {@link SecondInstance} so it can be exercised without a running game or a
 * second window: the argument rewriting is the part that silently breaks when a launcher passes an
 * unexpected shape, so it is worth having a test for it.</p>
 */
public final class CommandLine {

    private CommandLine() {
    }

    /** Directory used for the second window: a subdirectory of this window's game directory. */
    public static Path childGameDir(Path gameDir) {
        return gameDir.resolve("splitplay-p2");
    }

    /**
     * Replaces the per player values in place. Returns how many arguments were rewritten, which is
     * a quick way to see from the log whether the command looked like a game command at all.
     */
    public static int rewrite(List<String> command, Path gameDir, String username) {
        String childDir = childGameDir(gameDir).toString();
        String uuid = offlineUuid(username);
        int changed = 0;
        boolean sawGameDir = false;
        for (int i = 1; i < command.size() - 1; i++) {
            String argument = command.get(i);
            switch (argument) {
                case "--username" -> {
                    command.set(i + 1, username);
                    changed++;
                }
                case "--uuid" -> {
                    command.set(i + 1, uuid);
                    changed++;
                }
                case "--accessToken", "--clientId", "--xuid" -> {
                    command.set(i + 1, "0");
                    changed++;
                }
                case "--gameDir" -> {
                    command.set(i + 1, childDir);
                    sawGameDir = true;
                    changed++;
                }
                default -> {
                }
            }
        }
        if (!sawGameDir) {
            command.add("--gameDir");
            command.add(childDir);
            changed++;
        }
        return changed;
    }

    /**
     * Splits a command line on whitespace while keeping double quoted runs together. Minecraft's own
     * arguments never contain spaces, but a mod folder path may.
     */
    public static List<String> tokenise(String commandLine) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        // Tracks "an argument was seen", so that an explicitly empty one ("" on the command line)
        // is kept instead of being silently dropped.
        boolean started = false;
        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                started = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (started) {
                    tokens.add(token.toString());
                    token.setLength(0);
                    started = false;
                }
            } else {
                token.append(c);
                started = true;
            }
        }
        if (started) {
            tokens.add(token.toString());
        }
        return tokens;
    }

    /** Same scheme the game itself uses for offline players. */
    public static String offlineUuid(String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0F) | 0x30);
            hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);
            return new UUID(bytesToLong(hash, 0), bytesToLong(hash, 8)).toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private static long bytesToLong(byte[] bytes, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFFL);
        }
        return value;
    }
}
