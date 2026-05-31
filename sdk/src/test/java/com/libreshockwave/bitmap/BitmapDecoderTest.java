package com.libreshockwave.bitmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BitmapDecoderTest {

    @Test
    void decodesUncompressedDirector32BitRowsAsInterleavedArgb() {
        byte[] argb = new byte[] {
                (byte) 0xFF, (byte) 0xF9, (byte) 0xD6, (byte) 0xA0,
                (byte) 0xFF, (byte) 0x84, 0x63, 0x38
        };

        Bitmap bitmap = BitmapDecoder.decode(
                argb,
                2,
                1,
                32,
                Palette.SYSTEM_WIN_PALETTE,
                true,
                1200,
                8
        );

        assertEquals(0xFFF9D6A0, bitmap.getPixel(0, 0));
        assertEquals(0xFF846338, bitmap.getPixel(1, 0));
    }

    @Test
    void canApplyPalettedUseAlphaMatteIndex() {
        Bitmap bitmap = BitmapDecoder.decode(
                new byte[] { 0, (byte) 255 },
                2,
                1,
                8,
                Palette.SYSTEM_MAC_PALETTE,
                true,
                1200,
                2
        );

        int changed = bitmap.makePaletteIndexTransparent(0);

        assertEquals(1, changed);
        assertEquals(0x00FFFFFF, bitmap.getPixel(0, 0));
        assertEquals(0xFF000000, bitmap.getPixel(1, 0));
    }
}
