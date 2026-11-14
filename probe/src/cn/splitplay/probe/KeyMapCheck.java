package cn.splitplay.probe;

import cn.splitplay.input.KeyMap;

/**
 * Checks the Windows VK to GLFW key table without starting the game.
 *
 * <p>This exists because the table is easy to get subtly wrong and the symptom in game is "this key
 * does nothing", which is hard to attribute. Run it with {@code keymap-check.bat} after touching
 * {@link KeyMap}.</p>
 */
public final class KeyMapCheck {

    private static int pass;
    private static int fail;

    private KeyMapCheck() {
    }

    public static void main(String[] args) {
        check(0x41, 65, "A");
        check(0x30, 48, "0");
        check(0x20, 32, "space");
        check(0x1B, 256, "escape");
        check(0x0D, 257, "enter");
        check(0x09, 258, "tab");
        check(0x08, 259, "backspace");
        check(0x70, 290, "F1");
        check(0x72, 292, "F3");
        check(0x7B, 301, "F12");
        check(0xA0, 340, "left shift");
        check(0xA1, 344, "right shift");
        check(0xA2, 341, "left control");
        check(0xA3, 345, "right control");
        check(0xA4, 342, "left alt");
        check(0xA5, 346, "right alt");
        check(0x25, 263, "left arrow");
        check(0x27, 262, "right arrow");
        check(0x26, 265, "up arrow");
        check(0x28, 264, "down arrow");
        check(0x2E, 261, "delete");
        check(0x24, 268, "home");
        check(0x23, 269, "end");
        check(0xBA, 59, "semicolon");
        check(0xDE, 39, "apostrophe");
        check(0xDB, 91, "left bracket");
        check(0xC0, 96, "grave accent");
        check(0x60, 320, "keypad 0");
        check(0x6A, 332, "keypad multiply");
        check(0x5B, 343, "left windows");
        check(0x14, 280, "caps lock");
        check(0x2C, 283, "print screen");
        check(0x00, -1, "unknown");

        System.out.println("keymap: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void check(int vk, int expected, String what) {
        int actual = KeyMap.toGlfw(vk);
        if (actual == expected) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + what + ": vk=0x" + Integer.toHexString(vk)
                    + " got=" + actual + " expected=" + expected);
        }
    }
}
