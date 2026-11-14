package cn.splitplay.input;

/**
 * Translates Windows virtual key codes into the characters the game expects from {@code
 * KeyboardHandler.charTyped}.
 *
 * <p>This is the US layout only, which is what {@code keybd_event}/{@code SendInput} and raw input
 * report scancodes for on a standard keyboard. Text composition through an IME is out of scope
 * here: an IME runs on the focused window and cannot be split between two players.</p>
 */
final class KeyChars {

    private KeyChars() {
    }

    static int toChar(int vk, boolean shift) {
        if (vk >= 0x30 && vk <= 0x39) {
            return shift ? ")!@#$%^&*(".charAt(vk - 0x30) : vk;
        }
        if (vk >= 0x41 && vk <= 0x5A) {
            return shift ? vk : vk + 0x20;
        }
        if (vk >= 0x60 && vk <= 0x69) {
            return shift ? "0862941375".charAt(vk - 0x60) : '0' + (vk - 0x60);
        }
        switch (vk) {
            case 0x20:
                return ' ';
            case 0x6A:
                return '*';
            case 0x6B:
                return '+';
            case 0x6D:
                return shift ? '-' : '-';
            case 0x6E:
                return shift ? ',' : '.';
            case 0x6F:
                return '/';
            case 0xBA:
                return shift ? ':' : ';';
            case 0xBB:
                return shift ? '+' : '=';
            case 0xBC:
                return shift ? '<' : ',';
            case 0xBD:
                return shift ? '_' : '-';
            case 0xBE:
                return shift ? '>' : '.';
            case 0xBF:
                return shift ? '?' : '/';
            case 0xC0:
                return shift ? '~' : '`';
            case 0xDB:
                return shift ? '{' : '[';
            case 0xDC:
                return shift ? '|' : '\\';
            case 0xDD:
                return shift ? '}' : ']';
            case 0xDE:
                return shift ? '"' : '\'';
            default:
                return 0;
        }
    }
}
