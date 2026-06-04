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

    @Test
    void twoBitPixelsUseRawPaletteIndices() {
        Palette palette = paletteWithFirstColors(
                0x112233,
                0x445566,
                0x778899,
                0xAABBCC
        );

        Bitmap bitmap = BitmapDecoder.decode(
                new byte[] { (byte) 0b00011011 },
                4,
                1,
                2,
                palette,
                true,
                1200,
                1
        );

        assertEquals(0xFF112233, bitmap.getPixel(0, 0));
        assertEquals(0xFF445566, bitmap.getPixel(1, 0));
        assertEquals(0xFF778899, bitmap.getPixel(2, 0));
        assertEquals(0xFFAABBCC, bitmap.getPixel(3, 0));
    }

    @Test
    void fourBitPixelsUseRawPaletteIndices() {
        Palette palette = paletteWithFirstColors(
                0x010203,
                0x111213,
                0x212223,
                0x313233,
                0x414243,
                0x515253,
                0x616263,
                0x717273,
                0x818283,
                0x919293,
                0xA1A2A3,
                0xB1B2B3,
                0xC1C2C3,
                0xD1D2D3,
                0xE1E2E3,
                0xF1F2F3
        );

        Bitmap bitmap = BitmapDecoder.decode(
                new byte[] { 0x45, (byte) 0xF0 },
                4,
                1,
                4,
                palette,
                true,
                1200,
                2
        );

        assertEquals(0xFF414243, bitmap.getPixel(0, 0));
        assertEquals(0xFF515253, bitmap.getPixel(1, 0));
        assertEquals(0xFFF1F2F3, bitmap.getPixel(2, 0));
        assertEquals(0xFF010203, bitmap.getPixel(3, 0));
    }

    @Test
    void twoBitGrayscalePixelsScaleToFullRamp() {
        Bitmap bitmap = BitmapDecoder.decode(
                new byte[] { (byte) 0b00011011 },
                4,
                1,
                2,
                Palette.GRAYSCALE_PALETTE,
                true,
                1200,
                1
        );

        assertEquals(0xFFFFFFFF, bitmap.getPixel(0, 0));
        assertEquals(0xFFAAAAAA, bitmap.getPixel(1, 0));
        assertEquals(0xFF555555, bitmap.getPixel(2, 0));
        assertEquals(0xFF000000, bitmap.getPixel(3, 0));
    }

    @Test
    void fourBitGrayscalePixelsScaleToFullRamp() {
        Bitmap bitmap = BitmapDecoder.decode(
                new byte[] { 0x05, (byte) 0xAF },
                4,
                1,
                4,
                Palette.GRAYSCALE_PALETTE,
                true,
                1200,
                2
        );

        assertEquals(0xFFFFFFFF, bitmap.getPixel(0, 0));
        assertEquals(0xFFAAAAAA, bitmap.getPixel(1, 0));
        assertEquals(0xFF555555, bitmap.getPixel(2, 0));
        assertEquals(0xFF000000, bitmap.getPixel(3, 0));
    }

    @Test
    void twoBitSystemPalettePixelsUseCompactIndicesDirectly() {
        Bitmap bitmap = BitmapDecoder.decode(
                new byte[] { (byte) 0b00011011 },
                4,
                1,
                2,
                Palette.SYSTEM_MAC_PALETTE,
                true,
                1200,
                1
        );

        assertEquals(0xFFFFFFFF, bitmap.getPixel(0, 0));
        assertEquals(0xFFFFFFCC, bitmap.getPixel(1, 0));
        assertEquals(0xFFFFFF99, bitmap.getPixel(2, 0));
        assertEquals(0xFFFFFF66, bitmap.getPixel(3, 0));
    }

    @Test
    void fourBitSystemPalettePixelsUseCompactIndicesDirectly() {
        Bitmap bitmap = BitmapDecoder.decode(
                new byte[] { 0x05, (byte) 0xAF },
                4,
                1,
                4,
                Palette.SYSTEM_MAC_PALETTE,
                true,
                1200,
                2
        );

        assertEquals(0xFFFFFFFF, bitmap.getPixel(0, 0));
        assertEquals(0xFFFFFF00, bitmap.getPixel(1, 0));
        assertEquals(0xFFFFCC33, bitmap.getPixel(2, 0));
        assertEquals(0xFFFF9966, bitmap.getPixel(3, 0));
    }

    @Test
    void eightBitPixelsUseLowNibbleForCompactPalette() {
        Palette palette = compactPalette(
                0xFFFFFF,
                0xEEEEEE,
                0x222222,
                0x333333,
                0xE7F700,
                0xF7CE00,
                0xC68C00,
                0x735200,
                0xA57300,
                0x000000,
                0x000000,
                0x000000,
                0x000000,
                0x000000,
                0x000000,
                0x000000
        );

        Bitmap bitmap = BitmapDecoder.decode(
                new byte[] { 0x00, 0x45, 0x56, (byte) 0xF8 },
                4,
                1,
                8,
                palette,
                true,
                1200,
                4
        );

        assertEquals(0xFFFFFFFF, bitmap.getPixel(0, 0));
        assertEquals(0xFFF7CE00, bitmap.getPixel(1, 0));
        assertEquals(0xFFC68C00, bitmap.getPixel(2, 0));
        assertEquals(0xFFA57300, bitmap.getPixel(3, 0));
    }

    private static Palette paletteWithFirstColors(int... colors) {
        int[] palette = new int[256];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = 0x000000;
        }
        for (int i = 0; i < colors.length; i++) {
            palette[i] = colors[i];
        }
        return new Palette(palette, "test");
    }

    private static Palette compactPalette(int... colors) {
        return new Palette(colors, "compact");
    }
}
