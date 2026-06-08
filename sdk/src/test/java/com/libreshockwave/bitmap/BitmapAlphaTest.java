package com.libreshockwave.bitmap;

import org.junit.jupiter.api.Test;

import com.libreshockwave.bitmap.Palette.InkMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BitmapAlphaTest {

    @Test
    void nonNativeThirtyTwoBitAlphaIsExposedAsOpaqueAuthoredColor() {
        Bitmap bitmap = new Bitmap(2, 1, 32, new int[] {
                0x00F0F0F0,
                0x80123456
        });

        Bitmap opaque = bitmap.copyWithNonNativeAlphaOpaque();

        assertEquals(0xFFF0F0F0, opaque.getPixel(0, 0));
        assertEquals(0xFF123456, opaque.getPixel(1, 0));
    }

    @Test
    void nativeAlphaBitmapKeepsTransparentPixels() {
        Bitmap bitmap = new Bitmap(1, 1, 32, new int[] {0x00F0F0F0});
        bitmap.setNativeAlpha(true);

        Bitmap result = bitmap.copyWithNonNativeAlphaOpaque();

        assertSame(bitmap, result);
        assertEquals(0x00F0F0F0, result.getPixel(0, 0));
    }

    @Test
    void eightBitRgbFillQuantizesToImagePalette() {
        Bitmap bitmap = new Bitmap(2, 1, 8);
        bitmap.setImagePalette(Palette.SYSTEM_WIN_PALETTE);

        bitmap.fillRect(0, 0, 2, 1, 0xFFEEEEEE);

        assertEquals(0xFFF0F0F0, bitmap.getPixel(0, 0));
        assertEquals(0xFFF0F0F0, bitmap.getPixel(1, 0));
    }

    @Test
    void firstPaletteAssignmentQuantizesExistingEightBitRgbPixels() {
        Bitmap bitmap = new Bitmap(2, 1, 8);
        bitmap.fillRect(0, 0, 2, 1, 0xFFEEEEEE);

        int changed = bitmap.remapImagePalette(Palette.SYSTEM_WIN_PALETTE);

        assertEquals(2, changed);
        assertEquals(0xFFF0F0F0, bitmap.getPixel(0, 0));
        assertEquals(0xFFF0F0F0, bitmap.getPixel(1, 0));
    }

    @Test
    void systemPaletteThirtyTwoBitFillKeepsAuthoredRgb() {
        Bitmap bitmap = new Bitmap(2, 1, 32);
        bitmap.setImagePalette(Palette.SYSTEM_WIN_PALETTE);

        bitmap.fillRect(0, 0, 2, 1, 0xFFEEEEEE);

        assertEquals(0xFFEEEEEE, bitmap.getPixel(0, 0));
        assertEquals(0xFFEEEEEE, bitmap.getPixel(1, 0));
    }

    @Test
    void customPaletteThirtyTwoBitFillKeepsAuthoredRgb() {
        Bitmap bitmap = new Bitmap(2, 1, 32);
        bitmap.setImagePalette(new Palette(new int[] {0x000000, 0xF0F0F0}, "custom"));

        bitmap.fillRect(0, 0, 2, 1, 0xFFEEEEEE);

        assertEquals(0xFFEEEEEE, bitmap.getPixel(0, 0));
        assertEquals(0xFFEEEEEE, bitmap.getPixel(1, 0));
    }

    @Test
    void remapPaletteOnThirtyTwoBitRgbImageKeepsAuthoredPixels() {
        Palette oldPalette = new Palette(new int[] {0x000000, 0xFFFFFF}, "old");
        Palette newPalette = new Palette(new int[] {0xFFFFC8, 0x000000}, "new");
        Bitmap bitmap = new Bitmap(2, 1, 32, new int[] {
                0xFF000000,
                0xFFFFFFFF
        });
        bitmap.setImagePalette(oldPalette);

        int changed = bitmap.remapImagePalette(newPalette);

        assertEquals(0, changed);
        assertSame(newPalette, bitmap.getImagePalette());
        assertEquals(0xFF000000, bitmap.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, bitmap.getPixel(1, 0));
    }

    @Test
    void infoStandTextBackgroundSystemWinRemapKeepsNeutralOverlay() {
        Bitmap bitmap = new Bitmap(2, 1, 2);
        bitmap.setImagePalette(Palette.SYSTEM_MAC_PALETTE);
        bitmap.setDebugOwnerName("info_stand_txt_bg");
        bitmap.setPixelPaletteIndex(0, 0, 0, 0xFFFFFFFF);
        bitmap.setPixelPaletteIndex(1, 0, 3, 0xFFFFFF66);

        int changed = bitmap.remapImagePalette(Palette.SYSTEM_WIN_PALETTE);

        assertEquals(1, changed);
        assertEquals(0xFFFFFFFF, bitmap.getPixel(0, 0));
        assertEquals(0xFFFEFEFE, bitmap.getPixel(1, 0));

        Bitmap dest = new Bitmap(2, 1, 32);
        dest.fill(0xFF000000);
        Drawing.copyPixels(dest, bitmap, 0, 0, 0, 0, 2, 1,
                InkMode.BACKGROUND_TRANSPARENT, 51, null, 0xFFFFFF);

        assertEquals(0xFF000000, dest.getPixel(0, 0));
        assertEquals(0xFF323232, dest.getPixel(1, 0));
    }
}
