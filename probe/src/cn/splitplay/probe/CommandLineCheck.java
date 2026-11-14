package cn.splitplay.probe;

import cn.splitplay.launch.CommandLine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Offline check for the second window's command line handling: tokenising, the per player
 * substitutions, and the offline uuid scheme. Plain Java, no Minecraft, no window.
 */
public final class CommandLineCheck {

    private static int pass;
    private static int fail;

    private CommandLineCheck() {
    }

    public static void main(String[] args) {
        tokenise();
        rewrite();
        uuids();
        System.out.println("commandline: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void tokenise() {
        check("tokenise plain", List.of("a", "b", "c"),
                CommandLine.tokenise("a b c"));
        check("tokenise quoted space", List.of("--path", "C:/my games/x"),
                CommandLine.tokenise("--path \"C:/my games/x\""));
        check("tokenise empty quoted", List.of("a", "", "b"),
                CommandLine.tokenise("a \"\" b"));
        check("tokenise collapses runs", List.of("a", "b"),
                CommandLine.tokenise("  a   b  "));
        check("tokenise empty", List.of(), CommandLine.tokenise("   "));
    }

    private static void rewrite() {
        List<String> command = new ArrayList<>(Arrays.asList(
                "java.exe",
                "-Xmx3G",
                "net.fabricmc.loader.impl.launch.knot.KnotClient",
                "--username", "SP1",
                "--version", "1.21.1-Fabric-SP",
                "--gameDir", "E:/mc/versions/1.21.1-Fabric-SP",
                "--assetsDir", "E:/mc/assets",
                "--assetIndex", "17",
                "--uuid", "89a1a08d-056e-3a31-b073-f4e15d27448b",
                "--accessToken", "0",
                "--userType", "legacy",
                "--width", "1280",
                "--height", "720"));
        int changed = CommandLine.rewrite(command, java.nio.file.Path.of("E:/mc/versions/1.21.1-Fabric-SP"), "P2");
        // username, uuid, accessToken: the game directory is deliberately left alone, so it is not counted.
        check("rewrite count", 3, changed);
        check("rewrite username", "P2", value(command, "--username"));
        check("rewrite uuid", CommandLine.offlineUuid("P2"), value(command, "--uuid"));
        check("rewrite leaves gameDir alone", "E:/mc/versions/1.21.1-Fabric-SP",
                value(command, "--gameDir"));
        check("rewrite keeps version", "1.21.1-Fabric-SP", value(command, "--version"));
        check("rewrite keeps width", "1280", value(command, "--width"));
        check("rewrite keeps jvm flag", "-Xmx3G", command.get(1));
        check("rewrite keeps main class",
                "net.fabricmc.loader.impl.launch.knot.KnotClient", command.get(2));

        List<String> missing = new ArrayList<>(Arrays.asList(
                "java.exe", "KnotClient", "--username", "SP1"));
        int changed2 = CommandLine.rewrite(missing, java.nio.file.Path.of("E:/mc"), "P2");
        check("rewrite appends gameDir", 2, changed2);
        check("rewrite appended value", "E:\\mc",
                missing.get(missing.size() - 1));
        check("rewrite appended flag", "--gameDir", missing.get(missing.size() - 2));

        List<String> tokens = new ArrayList<>(Arrays.asList(
                "java.exe", "KnotClient", "--accessToken", "abc", "--clientId", "def", "--xuid", "ghi"));
        CommandLine.rewrite(tokens, java.nio.file.Path.of("E:/mc"), "P2");
        check("rewrite accessToken", "0", value(tokens, "--accessToken"));
        check("rewrite clientId", "0", value(tokens, "--clientId"));
        check("rewrite xuid", "0", value(tokens, "--xuid"));
    }

    private static void uuids() {
        // Known value: the launcher script produced this uuid for the name SP1.
        check("uuid SP1", "89a1a08d-056e-3a31-b073-f4e15d27448b", CommandLine.offlineUuid("SP1"));
        check("uuid P2 stable", CommandLine.offlineUuid("P2"), CommandLine.offlineUuid("P2"));
        check("uuid differs by name", false,
                CommandLine.offlineUuid("P1").equals(CommandLine.offlineUuid("P2")));
        String uuid = CommandLine.offlineUuid("P2");
        check("uuid length", 36, uuid.length());
        check("uuid version nibble", '3', uuid.charAt(14));
        check("uuid variant nibble", true, "89ab".indexOf(uuid.charAt(19)) >= 0);
        check("uuid P1 known prefix", true, CommandLine.offlineUuid("P1").startsWith("9"));
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

    private static String value(List<String> command, String flag) {
        for (int i = 0; i < command.size() - 1; i++) {
            if (flag.equals(command.get(i))) {
                return command.get(i + 1);
            }
        }
        return null;
    }
}
