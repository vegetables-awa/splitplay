package cn.splitplay.launch;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads this process' arguments exactly as Windows recorded them.
 *
 * <p>{@code ProcessHandle.arguments()} is not always available, and rebuilding the command line from
 * {@code sun.java.command} has to guess where the quotes were: a version called "Cobblemon Official
 * Modpack [Fabric]" then arrives as five arguments, the game ignores them, and the second window
 * starts as a bare Fabric install with none of the pack's mods. Windows already knows the splits, so
 * ask it.</p>
 */
final class NativeCommandLine {

    private interface Shell32 extends Library {
        Shell32 INSTANCE = Native.load("shell32", Shell32.class);

        Pointer CommandLineToArgvW(WString commandLine, IntByReference argumentCount);
    }

    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        Pointer GetCommandLineW();

        Pointer LocalFree(Pointer pointer);
    }

    private NativeCommandLine() {
    }

    /** The arguments of this process, the executable first, or null when Windows cannot tell. */
    static List<String> arguments() {
        try {
            Pointer commandLine = Kernel32.INSTANCE.GetCommandLineW();
            if (commandLine == null) {
                return null;
            }
            IntByReference count = new IntByReference();
            PointerByReference array = new PointerByReference();
            Pointer raw = Shell32.INSTANCE.CommandLineToArgvW(new WString(commandLine.getWideString(0)), count);
            if (raw == null || count.getValue() <= 0) {
                return null;
            }
            try {
                Pointer[] pointers = raw.getPointerArray(0, count.getValue());
                List<String> arguments = new ArrayList<>(pointers.length);
                for (Pointer pointer : pointers) {
                    arguments.add(pointer.getWideString(0));
                }
                return arguments;
            } finally {
                Kernel32.INSTANCE.LocalFree(raw);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}
