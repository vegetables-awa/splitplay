package cn.splitplay.input;

import org.lwjgl.glfw.GLFW;

/**
 * Translates a Windows virtual key code into the GLFW key code Minecraft expects.
 *
 * <p>Raw input reports Windows VK codes, but {@code KeyboardHandler.keyPress} takes GLFW key codes.
 * Letters, digits and space happen to share values; everything else does not - F3 is 0x72 on Windows
 * and 292 in GLFW, shift is 0xA0 versus 340, escape 0x1B versus 256. Feeding the VK code straight in
 * makes those keys do nothing at all.</p>
 */
public final class KeyMap {

    private KeyMap() {
    }

    public static int toGlfw(int vk) {
        switch (vk) {
            case 0x08: return GLFW.GLFW_KEY_BACKSPACE;
            case 0x09: return GLFW.GLFW_KEY_TAB;
            case 0x0D: return GLFW.GLFW_KEY_ENTER;
            case 0x10: return GLFW.GLFW_KEY_LEFT_SHIFT;
            case 0x11: return GLFW.GLFW_KEY_LEFT_CONTROL;
            case 0x12: return GLFW.GLFW_KEY_LEFT_ALT;
            case 0x13: return GLFW.GLFW_KEY_PAUSE;
            case 0x14: return GLFW.GLFW_KEY_CAPS_LOCK;
            case 0x1B: return GLFW.GLFW_KEY_ESCAPE;
            case 0x20: return GLFW.GLFW_KEY_SPACE;
            case 0x21: return GLFW.GLFW_KEY_PAGE_UP;
            case 0x22: return GLFW.GLFW_KEY_PAGE_DOWN;
            case 0x23: return GLFW.GLFW_KEY_END;
            case 0x24: return GLFW.GLFW_KEY_HOME;
            case 0x25: return GLFW.GLFW_KEY_LEFT;
            case 0x26: return GLFW.GLFW_KEY_UP;
            case 0x27: return GLFW.GLFW_KEY_RIGHT;
            case 0x28: return GLFW.GLFW_KEY_DOWN;
            case 0x2C: return GLFW.GLFW_KEY_PRINT_SCREEN;
            case 0x2D: return GLFW.GLFW_KEY_INSERT;
            case 0x2E: return GLFW.GLFW_KEY_DELETE;
            case 0x5B: return GLFW.GLFW_KEY_LEFT_SUPER;
            case 0x5C: return GLFW.GLFW_KEY_RIGHT_SUPER;
            case 0x5D: return GLFW.GLFW_KEY_MENU;
            case 0x6A: return GLFW.GLFW_KEY_KP_MULTIPLY;
            case 0x6B: return GLFW.GLFW_KEY_KP_ADD;
            case 0x6D: return GLFW.GLFW_KEY_KP_SUBTRACT;
            case 0x6E: return GLFW.GLFW_KEY_KP_DECIMAL;
            case 0x6F: return GLFW.GLFW_KEY_KP_DIVIDE;
            case 0x90: return GLFW.GLFW_KEY_NUM_LOCK;
            case 0x91: return GLFW.GLFW_KEY_SCROLL_LOCK;
            case 0xA0: return GLFW.GLFW_KEY_LEFT_SHIFT;
            case 0xA1: return GLFW.GLFW_KEY_RIGHT_SHIFT;
            case 0xA2: return GLFW.GLFW_KEY_LEFT_CONTROL;
            case 0xA3: return GLFW.GLFW_KEY_RIGHT_CONTROL;
            case 0xA4: return GLFW.GLFW_KEY_LEFT_ALT;
            case 0xA5: return GLFW.GLFW_KEY_RIGHT_ALT;
            case 0xBA: return GLFW.GLFW_KEY_SEMICOLON;
            case 0xBB: return GLFW.GLFW_KEY_EQUAL;
            case 0xBC: return GLFW.GLFW_KEY_COMMA;
            case 0xBD: return GLFW.GLFW_KEY_MINUS;
            case 0xBE: return GLFW.GLFW_KEY_PERIOD;
            case 0xBF: return GLFW.GLFW_KEY_SLASH;
            case 0xC0: return GLFW.GLFW_KEY_GRAVE_ACCENT;
            case 0xDB: return GLFW.GLFW_KEY_LEFT_BRACKET;
            case 0xDC: return GLFW.GLFW_KEY_BACKSLASH;
            case 0xDD: return GLFW.GLFW_KEY_RIGHT_BRACKET;
            case 0xDE: return GLFW.GLFW_KEY_APOSTROPHE;
            default:
                break;
        }
        if (vk >= 0x30 && vk <= 0x39) {
            return GLFW.GLFW_KEY_0 + (vk - 0x30);
        }
        if (vk >= 0x41 && vk <= 0x5A) {
            return GLFW.GLFW_KEY_A + (vk - 0x41);
        }
        if (vk >= 0x60 && vk <= 0x69) {
            return GLFW.GLFW_KEY_KP_0 + (vk - 0x60);
        }
        if (vk >= 0x70 && vk <= 0x7B) {
            return GLFW.GLFW_KEY_F1 + (vk - 0x70);
        }
        return GLFW.GLFW_KEY_UNKNOWN;
    }
}
