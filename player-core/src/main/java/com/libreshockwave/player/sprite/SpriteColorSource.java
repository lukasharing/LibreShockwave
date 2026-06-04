package com.libreshockwave.player.sprite;

/**
 * Director sprite colors can carry the same numeric value with different
 * meanings depending on the source expression.
 */
public enum SpriteColorSource {
    /** Packed RGB, e.g. color(255, 255, 255) or 0xFFFFFF. */
    RGB,
    /** Director color-number model used only by explicit callers that need the inverted ramp. */
    DIRECTOR_COLOR_NUMBER,
    /** Direct palette index from paletteIndex(n). */
    PALETTE_INDEX;

    public static SpriteColorSource forScoreColor(boolean rgbFlag) {
        return rgbFlag ? RGB : PALETTE_INDEX;
    }

    public static SpriteColorSource forLegacyExplicitColor(int value) {
        return value > 255 ? RGB : PALETTE_INDEX;
    }

    public static SpriteColorSource forLegacyScoreColor(int value) {
        return value > 255 ? RGB : PALETTE_INDEX;
    }
}
