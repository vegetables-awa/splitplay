package cn.splitplay;

import cn.splitplay.client.DeviceClaims;
import cn.splitplay.config.SplitConfig;
import cn.splitplay.input.InputRouter;
import cn.splitplay.launch.SplitLauncher;
import cn.splitplay.rawin.RawInputDevice;
import cn.splitplay.rawin.RawInputEvent;
import cn.splitplay.rawin.RawInputListener;
import cn.splitplay.rawin.RawInputService;
import cn.splitplay.rawin.RawInputSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Client bootstrap: loads the config, starts collecting raw input and reports what it found.
 *
 * <p>Collecting raw input does not interfere with the game by itself. Routing starts once a
 * keyboard and a mouse have been assigned to this window, which the device screen (later step)
 * writes into the config.
 */
public final class SplitPlayClient {

    /** Registering for raw input reports every existing device as "arrived"; ignore the burst. */
    private static final long DEVICE_CHANGE_DEBOUNCE_NANOS = 500_000_000L;

    private static final AtomicLong eventCount = new AtomicLong();

    /** Device label to event count, for devices this window ignored (see {@link #drainIgnoredEvents}). */
    private static final java.util.Map<String, Integer> ignoredEvents = new java.util.concurrent.ConcurrentHashMap<>();

    private static RawInputService service;
    private static SplitConfig config;
    private static Path configFile;
    private static Path gameDir;

    private static volatile long lastDeviceCheckNanos;
    private static volatile long lastDeviceChangeNanos;
    private static volatile List<String> deviceSignature = Collections.emptyList();

    /**
     * Whether the devices this window was assigned are actually plugged in right now.
     *
     * <p>Isolation drops every native event, so if the assigned hardware is missing the window would
     * swallow all input and become a window nobody can control. The gate in
     * {@link cn.splitplay.input.Isolation#isActive()} therefore also requires this flag.</p>
     */
    private static volatile boolean assignedDevicesPresent = true;

    private SplitPlayClient() {
    }

    public static SplitConfig config() {
        return config;
    }

    public static Path configFile() {
        return configFile;
    }

    /**
     * The Win32 handle of the game window, or 0 while the window does not exist yet.
     *
     * <p>{@code Window.getWindow()} is the GLFW window pointer, not the HWND: registering raw input or
     * subclassing the window procedure against that value fails with "is not a window".</p>
     */
    private static long nativeWindowHandle() {
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft == null || minecraft.getWindow() == null) {
                return 0L;
            }
            com.sun.jna.platform.win32.WinDef.HWND hwnd =
                    cn.splitplay.launch.NativeWindows.windowHandle(minecraft.getWindow().getWindow());
            return hwnd == null ? 0L : com.sun.jna.Pointer.nativeValue(hwnd.getPointer());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger attachAttempts =
            new java.util.concurrent.atomic.AtomicInteger();
    private static volatile boolean attachGaveUp;

    /**
     * Upgrades the early message-only sink to the game window once the window exists.
     *
     * <p>Called from the client tick: at mod-init time {@code Minecraft.getInstance()} is null, and the
     * pack-sized mod lists make the gap long enough that assuming otherwise breaks mouse input in the
     * world (a sink stops receiving mouse {@code WM_INPUT} after the cursor is grabbed).</p>
     */
    public static void ensureWindowAttached() {
        RawInputService current = service;
        if (current == null || current.attachedToGameWindow() || attachGaveUp) {
            return;
        }
        long handle = nativeWindowHandle();
        if (handle == 0L) {
            if (attachAttempts.incrementAndGet() > 600) {
                attachGaveUp = true;
                SplitPlay.LOGGER.warn("splitplay: gave up on the game window, keeping the separate sink");
            }
            return;
        }
        if (current.attachToWindow(handle)) {
            attachGaveUp = false;
            current.stopSink();
            SplitPlay.LOGGER.info("splitplay: raw input attached to the game window");
        } else if (attachAttempts.incrementAndGet() > 20) {
            attachGaveUp = true;
            SplitPlay.LOGGER.warn("splitplay: could not attach to the game window ({}), keeping the separate sink",
                    current.lastFailure());
        }
    }

    public static RawInputService service() {
        return service;
    }

    /** Re-reads the config file after the device screen wrote it, so routing follows immediately. */
    public static void reload() {
        if (configFile == null) {
            return;
        }
        config = SplitConfig.load(configFile);
        logDevices("reload");
        SplitPlay.LOGGER.info("splitplay config reloaded: role='{}' keyboard={} mouse={} active={}",
                config.role, describe(config.keyboard), describe(config.mouse), config.isolatesInput());
        DeviceClaims.publish(gameDir, config);
    }

    /**
     * True when the assigned keyboard and mouse are both present. False means this window stays
     * vanilla: dropping input nobody replays would leave the window uncontrollable.
     */
    public static boolean assignedDevicesPresent() {
        return assignedDevicesPresent;
    }

    /** Recomputes {@link #assignedDevicesPresent()} from a fresh device list. */
    private static void refreshAssignedDevices(List<RawInputDevice> devices) {
        SplitConfig current = config;
        if (current == null || !current.isComplete()) {
            assignedDevicesPresent = true;
            return;
        }
        boolean keyboard = false;
        boolean mouse = false;
        for (RawInputDevice device : devices) {
            // Type and path both have to agree before a device counts as the assigned one, otherwise a
            // same-model sibling that happens to share a path suffix could mark a missing device present.
            if (!keyboard && device.isKeyboard() && current.matchesKeyboard(device.path)) {
                keyboard = true;
            } else if (!mouse && device.isMouse() && current.matchesMouse(device.path)) {
                mouse = true;
            }
        }
        boolean present = keyboard && mouse;
        if (present != assignedDevicesPresent) {
            assignedDevicesPresent = present;
            if (present) {
                SplitPlay.LOGGER.info("splitplay: assigned devices are plugged in again, input routing resumes");
            } else {
                SplitPlay.LOGGER.warn("splitplay: assigned {} is not plugged in, this window keeps vanilla input"
                                + " until it is back (pick the devices again if it was moved to another port)",
                        !keyboard && !mouse ? "keyboard and mouse" : (keyboard ? "mouse" : "keyboard"));
            }
        }
    }

    /** This window's game directory: claims and the taskbar marker live relative to it. */
    public static Path gameDir() {
        return gameDir;
    }

    static void init() {
        // The second window shares this game directory (same pack, same configs, same resources), so
        // it keeps its own settings file instead of a separate game directory.
        configFile = SplitConfig.defaultFile(FabricLoader.getInstance().getConfigDir(),
                cn.splitplay.launch.SecondInstance.isChild()
                        ? cn.splitplay.launch.SecondInstance.CHILD_CONFIG_NAME
                        : null);
        gameDir = FabricLoader.getInstance().getGameDir();
        config = SplitConfig.load(configFile);
        SplitPlay.LOGGER.info("splitplay config {}: role='{}' keyboard={} mouse={} enabled={} look={} cursor={}",
                configFile, config.role, describe(config.keyboard), describe(config.mouse),
                config.enabled, config.sensitivity, config.cursorSensitivity);

        logDevices("startup");

        SplitLauncher.recoverTaskbarIfNeeded(gameDir);
        DeviceClaims.publish(gameDir, config);
        startPointerWatchdog();

        service = new RawInputService();
        service.addListener(new RawInputListener() {
            @Override
            public void onRawInput(RawInputEvent event) {
                onEvent(event);
            }

            @Override
            public void onDevicesChanged() {
                onDeviceChange();
            }
        });
        // Raw input is read from the game's own window: a message-only sink stops getting mouse
        // WM_INPUT once the game grabs the cursor, which is exactly the "nothing moves" state.
        long glfwWindow = nativeWindowHandle();
        if (glfwWindow != 0L && service.attachToWindow(glfwWindow)) {
            SplitPlay.LOGGER.info("splitplay: raw input attached to the game window");
        } else {
            // Mod initialisers run before the game window exists, so this is the normal path on a big
            // mod pack. Start the sink so keys work immediately, and upgrade to the window hook on the
            // first client tick -- a sink stops seeing mouse input once the game grabs the cursor.
            SplitPlay.LOGGER.warn("splitplay: the game window is not available yet ({}), starting a separate sink",
                    glfwWindow == 0L ? "no window handle" : service.lastFailure());
            service.start();
        }

        // Attaching is synchronous; the fallback start() is not, so the failure reason is checked later.
        Thread watcher = new Thread(() -> {
            try {
                Thread.sleep(400L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            String failure = service.lastFailure();
            if (failure != null) {
                SplitPlay.LOGGER.error("splitplay: raw input is not running ({})", failure);
            } else {
                SplitPlay.LOGGER.info("splitplay: raw input listening");
            }
        }, "splitplay-rawinput-check");
        watcher.setDaemon(true);
        watcher.start();
    }

    /**
     * Enumerates the devices, logs them and remembers the set. Returns the list so callers can pick
     * from it; an unchanged set is not logged twice.
     */
    public static List<RawInputDevice> logDevices(String why) {
        lastDeviceCheckNanos = System.nanoTime();
        List<RawInputDevice> devices = RawInputDevice.enumerate();
        refreshAssignedDevices(devices);
        List<String> signature = new ArrayList<>(devices.size());
        int keyboards = 0;
        int mice = 0;
        for (RawInputDevice device : devices) {
            signature.add(device.type + "|" + device.path);
            if (device.isKeyboard()) {
                keyboards++;
            } else if (device.isMouse()) {
                mice++;
            }
        }
        if (signature.equals(deviceSignature)) {
            return devices;
        }
        deviceSignature = signature;
        for (RawInputDevice device : devices) {
            SplitPlay.LOGGER.info("splitplay device ({}): {}", why, device);
        }
        SplitPlay.LOGGER.info("splitplay found {} keyboard(s) and {} mouse/mice", keyboards, mice);
        if (keyboards < 2 || mice < 2) {
            SplitPlay.LOGGER.warn("splitplay needs two keyboards and two mice for two players, "
                    + "but the system reports {} keyboard(s) and {} mouse/mice", keyboards, mice);
        }
        return devices;
    }

    private static void onDeviceChange() {
        long now = System.nanoTime();
        long previous = lastDeviceChangeNanos;
        // Device-change notifications arrive in bursts, and the raw input service coalesces them, so
        // one enumeration per burst is enough. This timer deliberately tracks only real notifications:
        // sharing it with logDevices() would let a config reload swallow the next hotplug.
        if (previous != 0L && now - previous < DEVICE_CHANGE_DEBOUNCE_NANOS) {
            return;
        }
        lastDeviceChangeNanos = now;
        // A device that just disappeared will never send the release for a key it was holding, so
        // vanilla has to be told to let go of everything before the new list is applied.
        InputRouter.requestRelease();
        logDevices("hotplug");
    }

    private static void onEvent(RawInputEvent event) {
        long n = eventCount.incrementAndGet();
        // Logging every event would flood the log at 1000 Hz; the first few are enough to prove the
        // plumbing works, and the routing layer gets the full stream through the listener.
        if (n <= 20) {
            SplitPlay.LOGGER.info("splitplay event: {}", event);
        } else if (n == 21) {
            SplitPlay.LOGGER.info("splitplay: further raw input events are not logged");
        }

        SplitConfig current = config;
        if (current == null || !current.isolatesInput()) {
            return;
        }
        // Only this window's own devices are replayed; everything else is left to the other
        // instance, which receives the same physical stream through its own raw input listener.
        boolean keyboardEvent = event.kind == RawInputEvent.Kind.KEY;
        boolean mine = keyboardEvent ? current.matchesKeyboard(event.devicePath)
                : current.matchesMouse(event.devicePath);
        if (mine) {
            InputRouter.noteRawEvent();
            InputRouter.offer(event);
        } else {
            // "It does nothing" almost always means the player is moving the other mouse. Count the
            // devices we ignored so the five-second line can name them.
            ignoredEvents.merge(shortLabel(event.devicePath), 1, Integer::sum);
        }
    }

    /** Suffix of a device path, which is enough to recognise a device in a log line. */
    private static String shortLabel(String path) {
        if (path == null) {
            return "?";
        }
        int cut = path.indexOf('#');
        return cut > 0 ? path.substring(0, cut) : path;
    }

    /**
     * Devices this window deliberately ignored since the last call, as {@code "label xN"}.
     *
     * <p>Read and cleared by the five-second report: a window that is assigned a mouse nobody is
     * holding looks exactly like a window whose input is broken, and this is what tells the two
     * apart.</p>
     */
    public static String drainIgnoredEvents() {
        if (ignoredEvents.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        ignoredEvents.forEach((label, count) -> {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(label).append(" x").append(count);
        });
        ignoredEvents.clear();
        return text.toString();
    }

    /**
     * True when the system focus is on one of the game windows: this one or its sibling.
     *
     * <p>Replaying is paused when the focus is somewhere else entirely. Otherwise using a browser
     * with the same mouse would keep turning the camera behind it, which is the single most
     * confusing thing about a background window that still reads its own devices. When the answer
     * cannot be determined the game keeps working, because a wrongly frozen window is worse than a
     * camera that turns when it should not.</p>
     */
    public static boolean anyGameWindowFocused() {
        long foreground;
        try {
            foreground = cn.splitplay.launch.NativeWindows.foregroundProcessId();
        } catch (Throwable t) {
            return true;
        }
        if (foreground <= 0L) {
            return true;
        }
        if (foreground == ProcessHandle.current().pid()) {
            return true;
        }
        Path directory = gameDir == null ? null : cn.splitplay.util.ClaimStore.directory(gameDir);
        if (directory == null) {
            return false;
        }
        for (cn.splitplay.util.ClaimStore.Claim claim : cn.splitplay.util.ClaimStore.readAll(directory)) {
            if (claim.pid != 0L && claim.pid == foreground) {
                return true;
            }
        }
        return false;
    }

    /**
     * Releases the pointer trap when no game window owns the foreground.
     *
     * <p>The render loop also checks this every frame, but a stalled or paused render loop would
     * leave a clipped cursor behind, and a clipped cursor makes the whole desktop unusable. This
     * watchdog is the guarantee: within a couple of hundred milliseconds the pointer belongs to
     * whoever has the focus, whatever the game is doing.</p>
     */
    private static void startPointerWatchdog() {
        Thread watchdog = new Thread(() -> {
            boolean parked = false;
            while (true) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    boolean ours = anyGameWindowFocused();
                    if (!ours) {
                        if (!parked) {
                            parked = true;
                            // Clip only: GLFW belongs to the window's own thread.
                            cn.splitplay.cursor.VirtualCursor.releaseClipOnly();
                        }
                    } else {
                        parked = false;
                    }
                } catch (Throwable error) {
                    // Never let the safety net crash the game thread it protects.
                }
            }
        }, "splitplay-pointer");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Short human readable form of a device path, for log lines. */
    public static String describe(String path) {
        return path == null || path.isEmpty() ? "<none>" : RawInputSupport.shortDevice(path);
    }
}
