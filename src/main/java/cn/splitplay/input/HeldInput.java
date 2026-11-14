package cn.splitplay.input;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What this window believes is physically held down, and how that maps onto the events vanilla wants.
 *
 * <p>Kept apart from {@code InputRouter} because all three input bugs this mod has had lived in exactly
 * this bookkeeping, and none of them could be seen without a game: a key that stayed pressed after its
 * device was unplugged (the player kept walking), a device that reported one press twice (attack fired
 * twice for one click), and a held key that never produced repeat characters (the chat box typed one
 * letter and stopped). The rules are worth being able to test without a window.</p>
 *
 * <p>Not thread safe on purpose: it is touched from the client thread only, like the vanilla handlers it
 * stands in for.</p>
 */
public final class HeldInput {

    /** {@code GLFW_PRESS}. */
    public static final int PRESS = 1;

    /** {@code GLFW_REPEAT}. */
    public static final int REPEAT = 2;

    /** {@code GLFW_RELEASE}. */
    public static final int RELEASE = 0;

    /** {@code GLFW_MOD_SHIFT}. */
    public static final int MOD_SHIFT = 0x0001;

    /** {@code GLFW_MOD_CONTROL}. */
    public static final int MOD_CONTROL = 0x0002;

    /** {@code GLFW_MOD_ALT}. */
    public static final int MOD_ALT = 0x0004;

    private final Set<Integer> keys = new TreeSet<>();
    private final Set<Integer> buttons = new TreeSet<>();

    /**
     * Records a key event and says which action vanilla should be told about.
     *
     * @return {@link #PRESS} for the first press, {@link #REPEAT} while it is still held and
     *     {@link #RELEASE} for the release
     */
    public int keyDown(int key) {
        return keys.add(key) ? PRESS : REPEAT;
    }

    /**
     * Records a key release.
     *
     * @return true when this window thought the key was down, false for a release nobody asked for
     */
    public boolean keyUp(int key) {
        return keys.remove(key);
    }

    /**
     * Records a button press.
     *
     * @return {@link #PRESS} for the first press, -1 when the button was already down, because a second
     *     press for one physical click would attack or use twice
     */
    public int buttonDown(int button) {
        return buttons.add(button) ? PRESS : -1;
    }

    /**
     * Records a button release.
     *
     * @return true when this window thought the button was down
     */
    public boolean buttonUp(int button) {
        return buttons.remove(button);
    }

    /** True while a press was recorded for {@code key} without its release. */
    public boolean isKeyHeld(int key) {
        return keys.contains(key);
    }

    /**
     * GLFW modifier bits for an event, derived from the keys already held plus the key in this event.
     *
     * <p>The key in the event has to be included: the press of Shift itself arrives before Shift is in
     * the set, and vanilla expects that press to already carry the Shift bit, otherwise Shift+click and
     * Shift+scroll lose the modifier on the first event of every chord.</p>
     */
    public int modifiers(int key, boolean down) {
        int modifiers = 0;
        boolean shift = keys.contains(ShiftKeys.LEFT) || keys.contains(ShiftKeys.RIGHT);
        boolean control = keys.contains(ControlKeys.LEFT) || keys.contains(ControlKeys.RIGHT);
        boolean alt = keys.contains(AltKeys.LEFT) || keys.contains(AltKeys.RIGHT);

        if (down) {
            if (key == ShiftKeys.LEFT || key == ShiftKeys.RIGHT) {
                shift = true;
            } else if (key == ControlKeys.LEFT || key == ControlKeys.RIGHT) {
                control = true;
            } else if (key == AltKeys.LEFT || key == AltKeys.RIGHT) {
                alt = true;
            }
        }

        if (shift) {
            modifiers |= MOD_SHIFT;
        }
        if (control) {
            modifiers |= MOD_CONTROL;
        }
        if (alt) {
            modifiers |= MOD_ALT;
        }
        return modifiers;
    }

    /** Keys this window still holds, in ascending order so a release pass is reproducible. */
    public List<Integer> heldKeys() {
        return List.copyOf(keys);
    }

    /** Mouse buttons this window still holds, in ascending order. */
    public List<Integer> heldButtons() {
        return List.copyOf(buttons);
    }

    public int heldKeyCount() {
        return keys.size();
    }

    public int heldButtonCount() {
        return buttons.size();
    }

    public boolean isEmpty() {
        return keys.isEmpty() && buttons.isEmpty();
    }

    public void clear() {
        keys.clear();
        buttons.clear();
    }

    /**
     * GLFW key codes of the modifier keys, spelled out here so this class needs neither GLFW nor a
     * running game. The offline check asserts these against GLFW itself.
     */
    public static final class ShiftKeys {
        /** {@code GLFW_KEY_LEFT_SHIFT}. */
        public static final int LEFT = 340;

        /** {@code GLFW_KEY_RIGHT_SHIFT}. */
        public static final int RIGHT = 344;

        private ShiftKeys() {
        }
    }

    /** GLFW key codes of the control keys. */
    public static final class ControlKeys {
        /** {@code GLFW_KEY_LEFT_CONTROL}. */
        public static final int LEFT = 341;

        /** {@code GLFW_KEY_RIGHT_CONTROL}. */
        public static final int RIGHT = 345;

        private ControlKeys() {
        }
    }

    /** GLFW key codes of the alt keys. */
    public static final class AltKeys {
        /** {@code GLFW_KEY_LEFT_ALT}. */
        public static final int LEFT = 342;

        /** {@code GLFW_KEY_RIGHT_ALT}. */
        public static final int RIGHT = 346;

        private AltKeys() {
        }
    }
}
