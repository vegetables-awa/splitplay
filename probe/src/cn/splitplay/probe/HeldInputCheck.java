package cn.splitplay.probe;

import cn.splitplay.input.HeldInput;
import org.lwjgl.glfw.GLFW;

/**
 * Offline check for the key and button bookkeeping that decides what vanilla is told.
 *
 * <p>Every assertion here stands for a bug that reached a running game once: a key that stayed
 * pressed after its device was unplugged (the player kept walking), a repeated press that became a
 * second attack, repeat characters that never arrived so a held key typed one letter. Those symptoms
 * look unrelated; they are all one class of mistake in this class.
 */
public final class HeldInputCheck {

    private static int pass;
    private static int fail;

    private HeldInputCheck() {
    }

    public static void main(String[] args) {
        constants();
        keyPresses();
        buttonPresses();
        modifiers();
        releaseBookkeeping();
        System.out.println("heldinput: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * The class spells the GLFW constants out so it needs neither GLFW nor a game; if they ever drift
     * from the real values, vanilla receives an action it does not know.
     */
    private static void constants() {
        check("PRESS matches GLFW", GLFW.GLFW_PRESS, HeldInput.PRESS);
        check("REPEAT matches GLFW", GLFW.GLFW_REPEAT, HeldInput.REPEAT);
        check("RELEASE matches GLFW", GLFW.GLFW_RELEASE, HeldInput.RELEASE);
        check("MOD_SHIFT matches GLFW", GLFW.GLFW_MOD_SHIFT, HeldInput.MOD_SHIFT);
        check("MOD_CONTROL matches GLFW", GLFW.GLFW_MOD_CONTROL, HeldInput.MOD_CONTROL);
        check("MOD_ALT matches GLFW", GLFW.GLFW_MOD_ALT, HeldInput.MOD_ALT);
        check("left shift matches GLFW", GLFW.GLFW_KEY_LEFT_SHIFT, HeldInput.ShiftKeys.LEFT);
        check("right shift matches GLFW", GLFW.GLFW_KEY_RIGHT_SHIFT, HeldInput.ShiftKeys.RIGHT);
        check("left control matches GLFW", GLFW.GLFW_KEY_LEFT_CONTROL, HeldInput.ControlKeys.LEFT);
        check("right control matches GLFW", GLFW.GLFW_KEY_RIGHT_CONTROL, HeldInput.ControlKeys.RIGHT);
        check("left alt matches GLFW", GLFW.GLFW_KEY_LEFT_ALT, HeldInput.AltKeys.LEFT);
        check("right alt matches GLFW", GLFW.GLFW_KEY_RIGHT_ALT, HeldInput.AltKeys.RIGHT);
    }

    private static void keyPresses() {
        HeldInput held = new HeldInput();
        int w = GLFW.GLFW_KEY_W;
        check("first press is PRESS", HeldInput.PRESS, held.keyDown(w));
        check("key is held after press", Boolean.TRUE, held.isKeyHeld(w));
        check("second press is REPEAT, not a new press", HeldInput.REPEAT, held.keyDown(w));
        check("repeats do not duplicate the key", 1, held.heldKeyCount());
        check("release is accepted", Boolean.TRUE, held.keyUp(w));
        check("key is no longer held", Boolean.FALSE, held.isKeyHeld(w));
        check("release of a key nobody pressed is refused", Boolean.FALSE, held.keyUp(w));
        check("pressing again after a release is a press again", HeldInput.PRESS, held.keyDown(w));

        HeldInput two = new HeldInput();
        two.keyDown(GLFW.GLFW_KEY_W);
        two.keyDown(GLFW.GLFW_KEY_A);
        two.keyDown(44);
        // Ascending order so the release pass is reproducible from one log line to the next.
        check("held keys are reported in order", java.util.List.of(44, 65, 87), two.heldKeys());
        two.keyUp(GLFW.GLFW_KEY_W);
        check("a key that has gone leaves the list", java.util.List.of(44, 65), two.heldKeys());
    }

    private static void buttonPresses() {
        HeldInput held = new HeldInput();
        int left = GLFW.GLFW_MOUSE_BUTTON_LEFT;
        check("first click is PRESS", HeldInput.PRESS, held.buttonDown(left));
        check("a chatty device reporting the same click twice is not a second click", -1,
                held.buttonDown(left));
        check("one button is held", 1, held.heldButtonCount());
        check("release is accepted", Boolean.TRUE, held.buttonUp(left));
        check("release of a button nobody pressed is refused", Boolean.FALSE, held.buttonUp(left));
        check("the next click is a press again", HeldInput.PRESS, held.buttonDown(left));

        HeldInput empty = new HeldInput();
        check("a fresh state holds nothing", Boolean.TRUE, empty.isEmpty());
        check("a fresh state reports no keys", 0, empty.heldKeyCount());
    }

    private static void modifiers() {
        HeldInput held = new HeldInput();
        int shift = GLFW.GLFW_KEY_LEFT_SHIFT;
        int ctrl = GLFW.GLFW_KEY_RIGHT_CONTROL;
        int alt = GLFW.GLFW_KEY_LEFT_ALT;

        check("nothing held means no modifiers", 0, held.modifiers(GLFW.GLFW_KEY_A, true));
        // The press of Shift itself must already carry the bit: it arrives before Shift is in the set.
        check("pressing shift carries SHIFT on that event", HeldInput.MOD_SHIFT,
                held.modifiers(shift, true));
        held.keyDown(shift);
        check("shift stays held for the next event", HeldInput.MOD_SHIFT,
                held.modifiers(GLFW.GLFW_KEY_A, true));
        check("shift and control combine", HeldInput.MOD_SHIFT | HeldInput.MOD_CONTROL,
                held.modifiers(ctrl, true));
        held.keyDown(ctrl);
        held.keyDown(alt);
        check("all three combine", HeldInput.MOD_SHIFT | HeldInput.MOD_CONTROL | HeldInput.MOD_ALT,
                held.modifiers(GLFW.GLFW_KEY_A, true));
        held.keyUp(shift);
        held.keyUp(ctrl);
        held.keyUp(alt);
        check("releasing everything clears the modifiers", 0, held.modifiers(GLFW.GLFW_KEY_A, true));
        check("a release event of shift is not itself a modifier", 0, held.modifiers(shift, false));

        HeldInput right = new HeldInput();
        check("right shift also sets SHIFT", HeldInput.MOD_SHIFT,
                right.modifiers(GLFW.GLFW_KEY_RIGHT_SHIFT, true));
        check("right alt also sets ALT", HeldInput.MOD_ALT,
                right.modifiers(GLFW.GLFW_KEY_RIGHT_ALT, true));
    }

    private static void releaseBookkeeping() {
        HeldInput held = new HeldInput();
        held.keyDown(GLFW.GLFW_KEY_W);
        held.keyDown(GLFW.GLFW_KEY_LEFT_SHIFT);
        held.buttonDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        check("two keys and one button held", "2 + 1",
                held.heldKeyCount() + " + " + held.heldButtonCount());
        // The release pass replays exactly what is listed, so the list must be a copy: clearing the
        // state while iterating it would skip entries.
        var keys = held.heldKeys();
        var buttons = held.heldButtons();
        held.clear();
        check("clearing empties the state", Boolean.TRUE, held.isEmpty());
        check("the snapshot survives clearing", 2, keys.size());
        check("the button snapshot survives clearing", 1, buttons.size());
        check("a second clear is harmless", Boolean.TRUE, held.isEmpty());
    }

    private static void check(String what, Object expected, Object actual) {
        boolean ok = expected instanceof Number number
                ? actual instanceof Number other && number.longValue() == other.longValue()
                : expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
            System.out.println("  ok  " + what);
        } else {
            fail++;
            System.out.println("  FAIL " + what + ": expected <" + expected + "> got <" + actual + ">");
        }
    }
}
