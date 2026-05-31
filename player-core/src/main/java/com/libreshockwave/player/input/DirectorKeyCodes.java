package com.libreshockwave.player.input;

/**
 * Maps host key codes to Director's Mac virtual keycodes.
 * Director uses Macintosh virtual key codes regardless of platform.
 *
 * Reference: Mac Carbon Events (kVK_*) values.
 */
public final class DirectorKeyCodes {

    private DirectorKeyCodes() {}

    // Desktop host virtual-key values, duplicated here to avoid UI toolkit dependencies in player-core.
    private static final int VK_ENTER = 10;
    private static final int VK_BACK_SPACE = 8;
    private static final int VK_TAB = 9;
    private static final int VK_ESCAPE = 27;
    private static final int VK_SPACE = 32;
    private static final int VK_DELETE = 127;
    private static final int VK_LEFT = 37;
    private static final int VK_UP = 38;
    private static final int VK_RIGHT = 39;
    private static final int VK_DOWN = 40;
    private static final int VK_HOME = 36;
    private static final int VK_END = 35;
    private static final int VK_PAGE_UP = 33;
    private static final int VK_PAGE_DOWN = 34;
    private static final int VK_F1 = 112;
    private static final int VK_F2 = 113;
    private static final int VK_F3 = 114;
    private static final int VK_F4 = 115;
    private static final int VK_F5 = 116;
    private static final int VK_F6 = 117;
    private static final int VK_F7 = 118;
    private static final int VK_F8 = 119;
    private static final int VK_F9 = 120;
    private static final int VK_F10 = 121;
    private static final int VK_F11 = 122;
    private static final int VK_F12 = 123;

    // Letter keys
    private static final int VK_A = 65;

    // Digit keys
    private static final int VK_0 = 48;

    /**
     * Convert a desktop host keyCode to a Director Mac virtual keycode.
     */
    public static int fromDesktopKeyCode(int desktopKeyCode) {
        // Special keys
        return switch (desktopKeyCode) {
            case VK_ENTER -> 36;       // kVK_Return
            case VK_TAB -> 48;         // kVK_Tab
            case VK_SPACE -> 49;       // kVK_Space
            case VK_BACK_SPACE -> 51;  // kVK_Delete (backspace)
            case VK_ESCAPE -> 53;      // kVK_Escape
            case VK_DELETE -> 117;     // kVK_ForwardDelete
            case VK_LEFT -> 123;       // kVK_LeftArrow
            case VK_RIGHT -> 124;      // kVK_RightArrow
            case VK_DOWN -> 125;       // kVK_DownArrow
            case VK_UP -> 126;         // kVK_UpArrow
            case VK_HOME -> 115;       // kVK_Home
            case VK_END -> 119;        // kVK_End
            case VK_PAGE_UP -> 116;    // kVK_PageUp
            case VK_PAGE_DOWN -> 121;  // kVK_PageDown

            // Function keys
            case VK_F1 -> 122;    // kVK_F1
            case VK_F2 -> 120;    // kVK_F2
            case VK_F3 -> 99;     // kVK_F3
            case VK_F4 -> 118;    // kVK_F4
            case VK_F5 -> 96;     // kVK_F5
            case VK_F6 -> 97;     // kVK_F6
            case VK_F7 -> 98;     // kVK_F7
            case VK_F8 -> 100;    // kVK_F8
            case VK_F9 -> 101;    // kVK_F9
            case VK_F10 -> 109;   // kVK_F10
            case VK_F11 -> 103;   // kVK_F11
            case VK_F12 -> 111;   // kVK_F12

            // Letter keys (A-Z) → Mac kVK_ANSI_A..Z
            default -> {
                if (desktopKeyCode >= VK_A && desktopKeyCode <= VK_A + 25) {
                    yield macLetterCode(desktopKeyCode - VK_A);
                }
                // Digit keys (0-9) → Mac kVK_ANSI_0..9
                if (desktopKeyCode >= VK_0 && desktopKeyCode <= VK_0 + 9) {
                    yield macDigitCode(desktopKeyCode - VK_0);
                }
                // Fallback: return host code (may not match Director expectations)
                yield desktopKeyCode;
            }
        };
    }

    /**
     * Convert a browser KeyboardEvent.keyCode to a Director Mac virtual keycode.
     * Explicit mapping because browser keyCodes differ from desktop host
     * virtual-key values for Enter (13 vs 10) and Delete (46 vs 127).
     */
    public static int fromBrowserKeyCode(int browserKeyCode) {
        return switch (browserKeyCode) {
            // Special keys (browser keyCode → Mac kVK_*)
            case 8 -> 51;     // Backspace → kVK_Delete
            case 9 -> 48;     // Tab → kVK_Tab
            case 13 -> 36;    // Enter → kVK_Return
            case 27 -> 53;    // Escape → kVK_Escape
            case 32 -> 49;    // Space → kVK_Space
            case 33 -> 116;   // PageUp → kVK_PageUp
            case 34 -> 121;   // PageDown → kVK_PageDown
            case 35 -> 119;   // End → kVK_End
            case 36 -> 115;   // Home → kVK_Home
            case 37 -> 123;   // Left → kVK_LeftArrow
            case 38 -> 126;   // Up → kVK_UpArrow
            case 39 -> 124;   // Right → kVK_RightArrow
            case 40 -> 125;   // Down → kVK_DownArrow
            case 46 -> 117;   // Delete → kVK_ForwardDelete

            // Function keys
            case 112 -> 122;  // F1 → kVK_F1
            case 113 -> 120;  // F2 → kVK_F2
            case 114 -> 99;   // F3 → kVK_F3
            case 115 -> 118;  // F4 → kVK_F4
            case 116 -> 96;   // F5 → kVK_F5
            case 117 -> 97;   // F6 → kVK_F6
            case 118 -> 98;   // F7 → kVK_F7
            case 119 -> 100;  // F8 → kVK_F8
            case 120 -> 101;  // F9 → kVK_F9
            case 121 -> 109;  // F10 → kVK_F10
            case 122 -> 103;  // F11 → kVK_F11
            case 123 -> 111;  // F12 → kVK_F12

            default -> {
                // Letter keys (A-Z): browser 65-90 → Mac kVK_ANSI_*
                if (browserKeyCode >= 65 && browserKeyCode <= 90) {
                    yield macLetterCode(browserKeyCode - 65);
                }
                // Digit keys (0-9): browser 48-57 → Mac kVK_ANSI_*
                if (browserKeyCode >= 48 && browserKeyCode <= 57) {
                    yield macDigitCode(browserKeyCode - 48);
                }
                yield browserKeyCode;
            }
        };
    }

    // Mac virtual keycodes for letters (QWERTY layout)
    // kVK_ANSI_A=0, kVK_ANSI_S=1, kVK_ANSI_D=2, kVK_ANSI_F=3, ...
    private static final int[] MAC_LETTER_CODES = {
        0,   // A → kVK_ANSI_A
        11,  // B → kVK_ANSI_B
        8,   // C → kVK_ANSI_C
        2,   // D → kVK_ANSI_D
        14,  // E → kVK_ANSI_E
        3,   // F → kVK_ANSI_F
        5,   // G → kVK_ANSI_G
        4,   // H → kVK_ANSI_H
        34,  // I → kVK_ANSI_I
        38,  // J → kVK_ANSI_J
        40,  // K → kVK_ANSI_K
        37,  // L → kVK_ANSI_L
        46,  // M → kVK_ANSI_M
        45,  // N → kVK_ANSI_N
        31,  // O → kVK_ANSI_O
        35,  // P → kVK_ANSI_P
        12,  // Q → kVK_ANSI_Q
        15,  // R → kVK_ANSI_R
        1,   // S → kVK_ANSI_S
        17,  // T → kVK_ANSI_T
        32,  // U → kVK_ANSI_U
        9,   // V → kVK_ANSI_V
        13,  // W → kVK_ANSI_W
        7,   // X → kVK_ANSI_X
        16,  // Y → kVK_ANSI_Y
        6,   // Z → kVK_ANSI_Z
    };

    private static int macLetterCode(int letterIndex) {
        if (letterIndex >= 0 && letterIndex < MAC_LETTER_CODES.length) {
            return MAC_LETTER_CODES[letterIndex];
        }
        return 0;
    }

    // Mac virtual keycodes for digits
    private static final int[] MAC_DIGIT_CODES = {
        29,  // 0 → kVK_ANSI_0
        18,  // 1 → kVK_ANSI_1
        19,  // 2 → kVK_ANSI_2
        20,  // 3 → kVK_ANSI_3
        21,  // 4 → kVK_ANSI_4
        23,  // 5 → kVK_ANSI_5
        22,  // 6 → kVK_ANSI_6
        26,  // 7 → kVK_ANSI_7
        28,  // 8 → kVK_ANSI_8
        25,  // 9 → kVK_ANSI_9
    };

    private static int macDigitCode(int digitIndex) {
        if (digitIndex >= 0 && digitIndex < MAC_DIGIT_CODES.length) {
            return MAC_DIGIT_CODES[digitIndex];
        }
        return 0;
    }
}
