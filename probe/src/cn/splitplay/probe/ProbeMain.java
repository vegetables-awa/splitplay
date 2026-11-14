package cn.splitplay.probe;

import cn.splitplay.rawin.RawInputDevice;
import cn.splitplay.rawin.RawInputEvent;
import cn.splitplay.rawin.RawInputListener;
import cn.splitplay.rawin.RawInputService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone harness for the raw-input layer. It does not touch Minecraft at all: run it, press keys
 * on each keyboard / move each mouse, and compare the paths with what the game saw.
 *
 * <p>Usage: {@code probe-rawinput.bat [--list] [--seconds N]}</p>
 */
public final class ProbeMain {

    public static void main(String[] args) throws Exception {
        boolean listOnly = false;
        int seconds = 0;
        for (int i = 0; i < args.length; i++) {
            if ("--list".equals(args[i])) {
                listOnly = true;
            } else if ("--seconds".equals(args[i]) && i + 1 < args.length) {
                seconds = Integer.parseInt(args[++i]);
            }
        }

        System.out.println("splitplay raw-input probe");
        System.out.println("  java    : " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("  jna     : " + com.sun.jna.Native.VERSION);
        System.out.println("  os      : " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch"));
        System.out.println();

        List<RawInputDevice> devices = RawInputDevice.enumerate();
        System.out.println("devices: " + devices.size());
        int keyboards = 0;
        int mice = 0;
        for (int i = 0; i < devices.size(); i++) {
            RawInputDevice d = devices.get(i);
            if (d.isKeyboard()) {
                keyboards++;
            } else if (d.isMouse()) {
                mice++;
            }
            System.out.printf("  [%d] %-10s %-32s %s%n",
                    i, d.isKeyboard() ? "KEYBOARD" : d.isMouse() ? "MOUSE" : "HID",
                    d.shortLabel(), d.path == null ? "<no path>" : d.path);
        }
        System.out.println("  -> keyboards=" + keyboards + " mice=" + mice);
        if (keyboards < 2 || mice < 2) {
            System.out.println("  !! fewer than two keyboards or two mice: two players cannot share this PC");
        }
        System.out.println();
        if (listOnly) {
            return;
        }

        RawInputService service = new RawInputService();
        AtomicInteger count = new AtomicInteger();
        service.addListener(new RawInputListener() {
            @Override
            public void onRawInput(RawInputEvent event) {
                int n = count.incrementAndGet();
                if (n <= 400) {
                    System.out.println("  " + event);
                } else if (n == 401) {
                    System.out.println("  ... (further events suppressed, still counting)");
                }
            }

            @Override
            public void onDevicesChanged() {
                System.out.println("  [device change]");
            }
        });
        service.start();
        Thread.sleep(300L);
        if (!service.isRunning()) {
            System.out.println("!! raw input service failed to start: " + service.lastFailure());
            System.exit(2);
        }
        System.out.println("raw input window is up. press keys / move mice now.");
        System.out.println(seconds > 0 ? "stops automatically after " + seconds + "s" : "press Enter here to stop.");
        System.out.println();

        long deadline = seconds > 0 ? System.currentTimeMillis() + seconds * 1000L : Long.MAX_VALUE;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
            if (stdinHasInput()) {
                break;
            }
        }

        service.close();
        System.out.println();
        System.out.println("events seen: " + count.get());
        System.out.println("done.");
    }

    /** True when the console has a pending line. Returns false instead of throwing on a broken stdin. */
    private static boolean stdinHasInput() {
        try {
            return System.in.available() > 0;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private ProbeMain() {
    }
}
