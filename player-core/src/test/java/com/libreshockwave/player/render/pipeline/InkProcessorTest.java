package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.bitmap.Bitmap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InkProcessorTest {

    @Test
    void backgroundTransparentRecoversAlphaFromAntialiased32BitText() {
        Bitmap src = new Bitmap(3, 1, 32, new int[] {
            0xFFFFFFFF,
            0xFFC8C8C8,
            0xFF000000
        });

        Bitmap result = InkProcessor.applyBackgroundTransparent(src, 0xFFFFFF);

        assertEquals(0x00000000, result.getPixel(0, 0));
        assertEquals(0x37000000, result.getPixel(1, 0));
        assertEquals(0xFF000000, result.getPixel(2, 0));
    }

    @Test
    void backgroundTransparentKeepsExactColorKeyForNon32BitBitmaps() {
        Bitmap src = new Bitmap(2, 1, 8, new int[] {
            0xFFFFFFFF,
            0xFF336699
        });

        Bitmap result = InkProcessor.applyBackgroundTransparent(src, 0xFFFFFF);

        assertEquals(0x00000000, result.getPixel(0, 0));
        assertEquals(0xFF336699, result.getPixel(1, 0));
    }

    @Test
    void backgroundTransparentKeepsOpaqueColored32BitUiPixels() {
        Bitmap src = new Bitmap(2, 1, 32, new int[] {
            0xFFFFFFFF,
            0xFF7B9498
        });

        Bitmap result = InkProcessor.applyBackgroundTransparent(src, 0xFFFFFF);

        assertEquals(0x00000000, result.getPixel(0, 0));
        assertEquals(0xFF7B9498, result.getPixel(1, 0));
    }

    @Test
    void matteRecoversAlphaFromAntialiased32BitEdges() {
        Bitmap src = new Bitmap(4, 1, 32, new int[] {
            0xFFFFFFFF,
            0x00DDDDDD,
            0xFF000000,
            0xFFFFFFFF
        });

        Bitmap result = InkProcessor.applyMatte(src, 0xFFFFFF);

        assertEquals(0x00000000, result.getPixel(0, 0));
        assertEquals(0x00000000, result.getPixel(1, 0));
        assertEquals(0xFF000000, result.getPixel(2, 0));
        assertEquals(0x00000000, result.getPixel(3, 0));
    }

    @Test
    void matteKeepsSolidColored32BitTile() {
        Bitmap src = new Bitmap(1, 1, 32, new int[] { 0xFFD4DDE1 });

        Bitmap result = InkProcessor.applyMatte(src, InkProcessor.resolveMatteColor(src, null, 0, false, null));

        assertEquals(0xFFD4DDE1, result.getPixel(0, 0));
    }

    @Test
    void matteUsesTopLeftColorForMixed32BitBitmap() {
        Bitmap src = new Bitmap(3, 3, 32, new int[] {
            0xFF2A6883, 0xFF2A6883, 0xFF2A6883,
            0xFF2A6883, 0xFFFFFFFF, 0xFF2A6883,
            0xFF2A6883, 0xFF2A6883, 0xFF2A6883
        });

        int matte = InkProcessor.resolveMatteColor(src, null, 0, false, null);
        Bitmap result = InkProcessor.applyMatte(src, matte);

        assertEquals(0x2A6883, matte);
        assertEquals(0x00000000, result.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, result.getPixel(1, 1));
    }

    @Test
    void matteFallsBackToWhiteWhenMixed32BitBitmapHasNoWhiteContent() {
        Bitmap src = new Bitmap(5, 1, 32, new int[] {
            0xFF88ADBD, 0xFF88ADBD, 0xFF88ADBD, 0xFF88ADBD, 0xFF000000
        });

        int matte = InkProcessor.resolveMatteColor(src, null, 0, false, null);
        Bitmap result = InkProcessor.applyMatte(src, matte);

        assertEquals(0xFFFFFF, matte);
        assertEquals(0xFF88ADBD, result.getPixel(0, 0));
        assertEquals(0xFF000000, result.getPixel(4, 0));
    }
}
