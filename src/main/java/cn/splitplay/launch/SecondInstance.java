package cn.splitplay.launch;

import cn.splitplay.SplitPlay;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Starts the second game window.
 *
 * <p>The command line is taken from the running process instead of being rebuilt from a version
 * file: whatever launcher started this window (Prism, PCL, a script) already produced a working
 * command, so reusing it is the only way to work with every launcher. Only the parts that must
 * differ are replaced: player name, uuid, and the game directory.</p>
 *
 * <p>When the process command line is not readable (it is not on every platform), the same command
 * is synthesised from facts the JVM always knows: the java executable, its own JVM arguments, its
 * class path, and {@code sun.java.command} for the main class plus game arguments.</p>
 *
 * <p>The child runs in <em>this</em> game directory. A separate directory was tried first and looked
 * wrong: the mods were there, but every configuration, resource pack and menu layout of the pack was
 * missing, so the second window behaved like a bare install of the same mods. Sharing the directory
 * keeps it the same pack; only the settings file, the player name and the console logs differ.</p>
 */
public final class SecondInstance {

    /** Set for the child process, so it does not offer to open a third window. */
    private static final String CHILD_ENV = "SPLITPLAY_CHILD";

    /** The second window's settings file, inside the shared config directory. */
    public static final String CHILD_CONFIG_NAME = "splitplay-p2.json";

    /** Working directory of the second window: only its log file lives here. */
    public static final String CHILD_CWD_DIR = "splitplay-p2-logs";

    private static final String DEFAULT_SECOND_NAME = "P2";

    private SecondInstance() {
    }

    public static boolean isChild() {
        return "1".equals(System.getenv(CHILD_ENV));
    }

    /** Directory used for the second window: this window's own directory. */
    public static Path childGameDir(Path gameDir) {
        return CommandLine.sharedGameDir(gameDir);
    }

    /**
     * Spawns the child and returns it. Blocking for a moment only; the game takes a while to show
     * its window, which {@link SplitLauncher} waits for separately.
     */
    public static Process start(Path gameDir, String username) throws IOException {
        List<String> command = currentCommand();
        int replaced = CommandLine.rewrite(command, gameDir, username);
        SplitPlay.LOGGER.info("splitplay: second window command has {} arguments ({} rewritten)",
                command.size(), replaced);

        Path configDir = gameDir.resolve("config");
        Files.createDirectories(configDir);
        seedChildConfig(configDir.resolve(CHILD_CONFIG_NAME));
        SplitPlay.LOGGER.info("splitplay: starting second window in {} (shared game directory, settings in {})",
                gameDir, CHILD_CONFIG_NAME);

        ProcessBuilder builder = new ProcessBuilder(command);
        // Same game directory on purpose: the second window has to be the same mod pack. The working
        // directory is the exception -- Minecraft writes logs/latest.log relative to it, and two
        // processes writing one file truncate and interleave each other's log.
        Path childCwd = gameDir.resolve(CHILD_CWD_DIR);
        Files.createDirectories(childCwd.resolve("logs"));
        builder.directory(childCwd.toFile());
        builder.environment().put(CHILD_ENV, "1");
        // Never a pipe: a piped stdout blocks the child the moment nobody drains it, which looks
        // exactly like a frozen second window. Files are drained by the disk.
        Path logDir = childCwd.resolve("logs");
        Files.createDirectories(logDir);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(
                logDir.resolve("splitplay-p2-stdout.txt").toFile()));
        builder.redirectError(ProcessBuilder.Redirect.appendTo(
                logDir.resolve("splitplay-p2-stderr.txt").toFile()));

        Process child = builder.start();
        SplitPlay.LOGGER.info("splitplay: second window started, pid {}", child.pid());
        return child;
    }

    /** Gives the child a starting config so the second window knows it is the second player. */
    private static void seedChildConfig(Path configFile) {
        if (Files.exists(configFile)) {
            return;
        }
        String json = "{\n"
                + "  \"role\": \"P2\",\n"
                + "  \"keyboard\": \"\",\n"
                + "  \"mouse\": \"\",\n"
                + "  \"sensitivity\": 1.0,\n"
                + "  \"enabled\": false,\n"
                + "  \"layout\": \"vertical\"\n"
                + "}\n";
        try {
            Files.writeString(configFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            SplitPlay.LOGGER.warn("splitplay: could not write the second window config: {}",
                    e.toString());
        }
    }

    /**
     * Builds the full java command line for the child: executable plus arguments, before the
     * per-player values are substituted.
     */
    static List<String> currentCommand() throws IOException {
        String java = exePath();
        // Windows keeps the exact splits, including arguments that contain spaces.
        List<String> nativeArguments = NativeCommandLine.arguments();
        if (nativeArguments != null && nativeArguments.size() > 1) {
            List<String> command = new ArrayList<>(nativeArguments);
            command.set(0, java);
            SplitPlay.LOGGER.info("splitplay: reusing this window's command line ({} argument(s))",
                    command.size() - 1);
            return command;
        }
        Optional<String[]> arguments = ProcessHandle.current().info().arguments();
        if (arguments.isPresent() && arguments.get().length > 0) {
            List<String> command = new ArrayList<>(arguments.get().length + 1);
            command.add(java);
            command.addAll(List.of(arguments.get()));
            SplitPlay.LOGGER.info("splitplay: reusing this window's command line ({})",
                    ProcessHandle.current().info().command().orElse("?"));
            return command;
        }

        // The process command line is hidden: rebuild it from what the JVM knows about itself.
        String commandLine = System.getProperty("sun.java.command");
        if (commandLine == null || commandLine.isBlank()) {
            throw new IOException("cannot read this process' command line");
        }
        List<String> parts = CommandLine.tokenise(commandLine);
        if (parts.isEmpty()) {
            throw new IOException("cannot parse this process' command line");
        }
        String mainClass = parts.get(0);
        List<String> command = new ArrayList<>();
        command.add(java);
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(parts.subList(1, parts.size()));
        SplitPlay.LOGGER.info("splitplay: rebuilt this window's command line ({} main class {})",
                java, mainClass);
        return command;
    }

    private static String exePath() {
        Optional<String> command = ProcessHandle.current().info().command();
        if (command.isPresent() && !command.get().isBlank()) {
            return command.get();
        }
        String home = System.getProperty("java.home");
        if (home != null && !home.isBlank()) {
            File candidate = new File(new File(home, "bin"), "java.exe");
            return candidate.getAbsolutePath();
        }
        return "java";
    }

    /** Same scheme the game itself uses for offline players. */
    public static String offlineUuid(String name) {
        return CommandLine.offlineUuid(name);
    }

    static String defaultSecondName() {
        return DEFAULT_SECOND_NAME;
    }
}
