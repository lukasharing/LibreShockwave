package com.libreshockwave.bitmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DrawingMatteTest {

    @Test
    void createMatteUsesSourceAlphaLayer() {
        Bitmap src = new Bitmap(4, 1, 32, new int[] {
                0xFFFFFFFF,
                0x00DDDDDD,
                0xFF000000,
                0x80AA0000
        });
        src.setNativeAlpha(true);

        Bitmap matte = Drawing.createMatte(src);

        assertEquals(0xFFFFFFFF, matte.getPixel(0, 0));
        assertEquals(0x00FFFFFF, matte.getPixel(1, 0));
        assertEquals(0xFFFFFFFF, matte.getPixel(2, 0));
        assertEquals(0x80FFFFFF, matte.getPixel(3, 0));
    }

    @Test
    void createMatteHonorsAlphaThreshold() {
        Bitmap src = new Bitmap(3, 1, 32, new int[] {
                0x7FFFFFFF,
                0x80FFFFFF,
                0xFFFFFFFF
        });
        src.setNativeAlpha(true);

        Bitmap matte = Drawing.createMatte(src, 0x80);

        assertEquals(0x00FFFFFF, matte.getPixel(0, 0));
        assertEquals(0x80FFFFFF, matte.getPixel(1, 0));
        assertEquals(0xFFFFFFFF, matte.getPixel(2, 0));
    }

    @Test
    void createMatteFallsBackToWhiteBorderFloodFillForOpaqueImages() {
        Bitmap src = new Bitmap(3, 3, 32, new int[] {
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFF224466, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF
        });

        Bitmap matte = Drawing.createMatte(src);

        assertEquals(0x00FFFFFF, matte.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, matte.getPixel(1, 1));
        assertEquals(0x00FFFFFF, matte.getPixel(2, 2));
    }


    @Test
    void createMatteKeepsNearWhiteBorderPixelsOpaque() {
        Bitmap src = new Bitmap(3, 3, 32, new int[] {
                0xFFFEFEFE, 0xFFFCFCFC, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFF224466, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF
        });

        Bitmap matte = Drawing.createMatte(src);

        assertEquals(0xFFFFFFFF, matte.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, matte.getPixel(1, 0));
        assertEquals(0xFFFFFFFF, matte.getPixel(1, 1));
    }

    @Test
    void createMatteTreatsNonNativeAlpha32BitImagesAsWhiteFloodFill() {
        Bitmap src = new Bitmap(3, 3, 32, new int[] {
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0x40000000, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF
        });

        Bitmap matte = Drawing.createMatte(src);

        assertEquals(0x00FFFFFF, matte.getPixel(0, 0));
        assertEquals(0x40FFFFFF, matte.getPixel(1, 1));
        assertEquals(0x00FFFFFF, matte.getPixel(2, 2));
    }

    @Test
    void createMattePreservesInternalTransparentPixelsDuringFloodFill() {
        Bitmap src = new Bitmap(3, 3, 32, new int[] {
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0x00000000, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF
        });

        Bitmap matte = Drawing.createMatte(src);

        assertEquals(0x00FFFFFF, matte.getPixel(0, 0));
        assertEquals(0x00FFFFFF, matte.getPixel(1, 1),
                "Flood-fill matte extraction must not turn existing transparent holes opaque");
        assertEquals(0x00FFFFFF, matte.getPixel(2, 2));
    }

    @Test
    void createMatteUsesPaletteZeroForIndexedFloodFill() {
        Bitmap src = new Bitmap(3, 3, 8, new int[] {
            0xFF000000, 0xFF000000, 0xFF000000,
            0xFF000000, 0xFF33CCFF, 0xFF000000,
            0xFF000000, 0xFF000000, 0xFF000000
        });
        src.setPaletteIndices(new byte[] {
            0, 0, 0,
            0, 7, 0,
            0, 0, 0
        });

        Bitmap matte = Drawing.createMatte(src);

        assertEquals(0x00FFFFFF, matte.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, matte.getPixel(1, 1));
        assertEquals(0x00FFFFFF, matte.getPixel(2, 2));
    }

    @Test
    void createMattePrefersPaletteZeroOverDominantIndexedArtworkEdge() {
        Bitmap src = new Bitmap(5, 5, 8, new int[] {
                0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000,
                0xFF000000, 0xFFD6B44A, 0xFFD6B44A, 0xFFD6B44A, 0xFF000000,
                0xFFFFFFFF, 0xFFD6B44A, 0xFFD6B44A, 0xFFD6B44A, 0xFF000000,
                0xFF000000, 0xFFD6B44A, 0xFFD6B44A, 0xFFD6B44A, 0xFF000000,
                0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000
        });
        src.setPaletteIndices(new byte[] {
                5, 5, 5, 5, 5,
                5, 8, 8, 8, 5,
                0, 8, 8, 8, 5,
                5, 8, 8, 8, 5,
                5, 5, 5, 5, 5
        });

        Bitmap matte = Drawing.createMatte(src);

        assertEquals(0xFFFFFFFF, matte.getPixel(0, 0),
                "dominant indexed artwork borders must not become the matte when palette slot 0 is present");
        assertEquals(0x00FFFFFF, matte.getPixel(0, 2),
                "edge-connected palette slot 0 remains the authored indexed matte");
        assertEquals(0xFFFFFFFF, matte.getPixel(1, 1));
        assertEquals(0xFFFFFFFF, matte.getPixel(4, 4));
    }

    @Test
    void createMatteUsesDominantBlackBorderFloodFillForRgbTextImages() {
        Bitmap src = new Bitmap(3, 3, 32, new int[] {
                0xFF000000, 0xFF000000, 0xFF000000,
                0xFF000000, 0xFFFFFFFF, 0xFF000000,
                0xFF000000, 0xFF000000, 0xFF000000
        });
        src.markScriptModified();

        Bitmap matte = Drawing.createMatte(src);

        assertEquals(0x00FFFFFF, matte.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, matte.getPixel(1, 1));
        assertEquals(0x00FFFFFF, matte.getPixel(2, 2));
    }

    @Test
    void createMatteKeepsBlackOutlinedRgbArtworkOpaque() {
        Bitmap src = blackOutlinedFlagBitmap();

        Bitmap matte = Drawing.createMatte(src);

        assertEquals(0xFFFFFFFF, matte.getPixel(0, 0),
                "black artwork outlines touching the edge are visible pixels, not inferred RGB matte");
        assertEquals(0xFFFFFFFF, matte.getPixel(2, 2),
                "white stripes enclosed by the black outline must not be flood-filled away");
        assertEquals(0xFFFFFFFF, matte.getPixel(4, 4));
    }

    @Test
    void copiedRuntimeTextImagesKeepDominantRgbMatteRule() {
        Bitmap src = new Bitmap(3, 3, 32, new int[] {
                0xFF000000, 0xFF000000, 0xFF000000,
                0xFF000000, 0xFFFFFFFF, 0xFF000000,
                0xFF000000, 0xFF000000, 0xFF000000
        });
        src.markScriptModified();

        Bitmap matte = Drawing.createMatte(src.copy());

        assertEquals(0x00FFFFFF, matte.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, matte.getPixel(1, 1));
        assertEquals(0x00FFFFFF, matte.getPixel(2, 2));
    }

    @Test
    void matteCopyPixelsKeepsColoredIndexedFrameOpaque() {
        Bitmap dest = new Bitmap(3, 3, 32, new int[] {
                0xFF112233, 0xFF112233, 0xFF112233,
                0xFF112233, 0xFF112233, 0xFF112233,
                0xFF112233, 0xFF112233, 0xFF112233
        });
        Bitmap src = new Bitmap(3, 3, 8, new int[] {
                0xFF6794A7, 0xFF6794A7, 0xFF6794A7,
                0xFF6794A7, 0xFFEFEFEF, 0xFF6794A7,
                0xFF6794A7, 0xFF6794A7, 0xFF6794A7
        });
        src.setPaletteIndices(new byte[] {
                4, 4, 4,
                4, 9, 4,
                4, 4, 4
        });

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 3, 3, Palette.InkMode.MATTE, 255);

        assertEquals(0xFF6794A7, dest.getPixel(0, 0));
        assertEquals(0xFFEFEFEF, dest.getPixel(1, 1));
        assertEquals(0xFF6794A7, dest.getPixel(2, 2));
    }

    @Test
    void applyFloodFillTransparencyUsesSameIndexedMatteRule() {
        Bitmap src = new Bitmap(3, 3, 8, new int[] {
            0xFF000000, 0xFF000000, 0xFF000000,
            0xFF000000, 0xFF33CCFF, 0xFF000000,
            0xFF000000, 0xFF000000, 0xFF000000
        });
        src.setPaletteIndices(new byte[] {
            0, 0, 0,
            0, 7, 0,
            0, 0, 0
        });

        Bitmap stripped = Drawing.applyFloodFillTransparency(src);

        assertEquals(0x00000000, stripped.getPixel(0, 0));
        assertEquals(0xFF33CCFF, stripped.getPixel(1, 1));
        assertEquals(0x00000000, stripped.getPixel(2, 2));
    }

    @Test
    void createMatteKeepsSolidUniformBitmapOpaque() {
        Bitmap src = new Bitmap(3, 3, 32, new int[] {
            0xFF020304, 0xFF020304, 0xFF020304,
            0xFF020304, 0xFF020304, 0xFF020304,
            0xFF020304, 0xFF020304, 0xFF020304
        });

        Bitmap matte = Drawing.createMatte(src);

        assertEquals(0xFFFFFFFF, matte.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, matte.getPixel(1, 1));
        assertEquals(0xFFFFFFFF, matte.getPixel(2, 2));
    }

    @Test
    void matteCopyPixelsKeepsSolidColoredTileContent() {
        Bitmap dest = new Bitmap(1, 1, 32);
        Bitmap src = new Bitmap(1, 1, 32, new int[] { 0xFFD4DDE1 });

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 1, 1, Palette.InkMode.MATTE, 255);

        assertEquals(0xFFD4DDE1, dest.getPixel(0, 0));
    }

    @Test
    void matteCopyPixelsRemovesDominantRgbBoundingPixels() {
        Bitmap dest = new Bitmap(3, 3, 32, new int[] {
                0xFF112233, 0xFF112233, 0xFF112233,
                0xFF112233, 0xFF112233, 0xFF112233,
                0xFF112233, 0xFF112233, 0xFF112233
        });
        Bitmap src = new Bitmap(3, 3, 32, new int[] {
                0xFF2A6883, 0xFF2A6883, 0xFF2A6883,
                0xFF2A6883, 0xFFFFFFFF, 0xFF2A6883,
                0xFF2A6883, 0xFF2A6883, 0xFF2A6883
        });

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 3, 3, Palette.InkMode.MATTE, 255);

        assertEquals(0xFF112233, dest.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, dest.getPixel(1, 1));
    }

    @Test
    void matteCopyPixelsStripsDominantRgbFrameAndKeepsContent() {
        Bitmap dest = new Bitmap(4, 4, 32, new int[] {
                0xFF112233, 0xFF112233, 0xFF112233, 0xFF112233,
                0xFF112233, 0xFF112233, 0xFF112233, 0xFF112233,
                0xFF112233, 0xFF112233, 0xFF112233, 0xFF112233,
                0xFF112233, 0xFF112233, 0xFF112233, 0xFF112233
        });
        Bitmap src = new Bitmap(4, 4, 32, new int[] {
                0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000,
                0xFF000000, 0xFF2A6883, 0xFF2A6883, 0xFF000000,
                0xFF000000, 0xFF2A6883, 0xFF2A6883, 0xFF000000,
                0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000
        });
        src.markScriptModified();

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 4, 4, Palette.InkMode.MATTE, 255);

        assertEquals(0xFF112233, dest.getPixel(0, 0));
        assertEquals(0xFF2A6883, dest.getPixel(1, 1));
        assertEquals(0xFF2A6883, dest.getPixel(2, 2));
        assertEquals(0xFF112233, dest.getPixel(3, 3));
    }

    @Test
    void matteCopyPixelsKeepsBlackOutlineAroundRgbArtwork() {
        Bitmap dest = new Bitmap(5, 5, 32);
        dest.fill(0xFF778899);
        Bitmap src = blackOutlinedFlagBitmap();

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 5, 5, Palette.InkMode.MATTE, 255);

        assertEquals(0xFF000000, dest.getPixel(0, 0),
                "matte copy must preserve black outline pixels from ordinary RGB artwork");
        assertEquals(0xFFFFFFFF, dest.getPixel(2, 2),
                "interior white artwork enclosed by the outline must remain visible");
        assertEquals(0xFFFF66AA, dest.getPixel(3, 1));
    }

    void matteCopyPixelsKeepsMixed32BitNoWhiteEdgeStripOpaque() {
        Bitmap dest = new Bitmap(5, 1, 32);
        Bitmap src = new Bitmap(5, 1, 32, new int[] {
                0xFF88ADBD, 0xFF88ADBD, 0xFF88ADBD, 0xFF88ADBD, 0xFF000000
        });

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 5, 1, Palette.InkMode.MATTE, 255);

        assertEquals(0xFF88ADBD, dest.getPixel(0, 0));
        assertEquals(0xFF000000, dest.getPixel(4, 0));
    }

    @Test
    void matteCopyPixelsHonorsNativeAlphaInsteadOfWhiteFloodFill() {
        Bitmap dest = new Bitmap(1, 1, 32, new int[] { 0xFF000000 });
        Bitmap src = new Bitmap(1, 1, 32, new int[] { 0xFFFFFFFF });
        src.setNativeAlpha(true);

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 1, 1, Palette.InkMode.MATTE, 255);

        assertEquals(0xFFFFFFFF, dest.getPixel(0, 0));
    }

    @Test
    void darkenCopyPixelsOverwritesOldDestinationGhosts() {
        Bitmap dest = new Bitmap(1, 1, 32, new int[] { 0xFF204080 });
        Bitmap src = new Bitmap(1, 1, 32, new int[] { 0xFFC08020 });

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 1, 1, Palette.InkMode.DARKEN, 255);

        assertEquals(0xFFC08020, dest.getPixel(0, 0));
    }

    @Test
    void lightenCopyPixelsOverwritesOldDestinationGhosts() {
        Bitmap dest = new Bitmap(1, 1, 32, new int[] { 0xFF103050 });
        Bitmap src = new Bitmap(1, 1, 32, new int[] { 0xFFE0C060 });

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 1, 1, Palette.InkMode.LIGHTEN, 255);

        assertEquals(0xFFE0C060, dest.getPixel(0, 0));
    }

    @Test
    void maskCopyPixelsUsesSourceBrightnessAsOpacity() {
        Bitmap dest = new Bitmap(3, 1, 32, new int[] {
                0xFFFFFFFF,
                0xFFFFFFFF,
                0xFFFFFFFF
        });
        Bitmap src = new Bitmap(3, 1, 32, new int[] {
                0xFF009999,
                0xFFFFFFFF,
                0xFF000000
        });

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 3, 1, Palette.InkMode.MASK, 255);

        assertEquals(0xFF94D4D4, dest.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, dest.getPixel(1, 0));
        assertEquals(0xFFFFFFFF, dest.getPixel(2, 0));
    }

    @Test
    void copyPixelsMaskImageUsesBlackPixelsAsMaskForNonAlphaImages() {
        Bitmap dest = new Bitmap(3, 1, 32, new int[] {
                0xFFFFFFFF,
                0xFFFFFFFF,
                0xFFFFFFFF
        });
        Bitmap src = new Bitmap(3, 1, 32, new int[] {
                0xFF000000,
                0xFF000000,
                0xFF000000
        });
        Bitmap mask = new Bitmap(3, 1, 8, new int[] {
                0xFFFFFFFF,
                0xFF000000,
                0xFFFFFFFF
        });

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 3, 1,
                Palette.InkMode.COPY, 255, mask);

        assertEquals(0xFFFFFFFF, dest.getPixel(0, 0));
        assertEquals(0xFF000000, dest.getPixel(1, 0));
        assertEquals(0xFFFFFFFF, dest.getPixel(2, 0));
    }

    @Test
    void matteCopyIntoEightBitImageBuildsBlackTextMaskFromBlackOnWhiteSource() {
        Bitmap mask = new Bitmap(3, 3, 8, new int[] {
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF
        });
        Bitmap text = new Bitmap(3, 3, 32, new int[] {
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF
        });

        Drawing.copyPixels(mask, text, 0, 0, 0, 0, 3, 3, Palette.InkMode.MATTE, 255);

        assertEquals(0xFFFFFFFF, mask.getPixel(0, 0));
        assertEquals(0xFF000000, mask.getPixel(1, 1));
        assertEquals(0xFFFFFFFF, mask.getPixel(2, 0));
    }

    @Test
    void matteCopyIntoEightBitImageBuildsBlackTextMaskFromWhiteOnBlackSource() {
        Bitmap mask = new Bitmap(3, 3, 8, new int[] {
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF
        });
        Bitmap text = new Bitmap(3, 3, 32, new int[] {
                0xFF000000, 0xFF000000, 0xFF000000,
                0xFF000000, 0xFFFFFFFF, 0xFF000000,
                0xFF000000, 0xFF000000, 0xFF000000
        });

        Drawing.copyPixels(mask, text, 0, 0, 0, 0, 3, 3, Palette.InkMode.MATTE, 255);

        assertEquals(0xFFFFFFFF, mask.getPixel(0, 0));
        assertEquals(0xFF000000, mask.getPixel(1, 1));
        assertEquals(0xFFFFFFFF, mask.getPixel(2, 0));
    }

    @Test
    void matteCopyOfColoredArtworkIntoWhiteEightBitImagePreservesSourceColor() {
        Bitmap dest = new Bitmap(1, 1, 8, new int[] {
                0xFFFFFFFF
        });
        Bitmap src = new Bitmap(1, 1, 32, new int[] {
                0xFFD4DDE1
        });

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 1, 1, Palette.InkMode.MATTE, 255);

        assertEquals(0xFFD4DDE1, dest.getPixel(0, 0));
    }

    @Test
    void matteCopyOfDenseGrayscaleUiStripIntoEightBitImagePreservesArtwork() {
        Bitmap dest = new Bitmap(5, 3, 8, new int[] {
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF
        });
        Bitmap src = new Bitmap(5, 3, 32, new int[] {
                0xFF000000, 0xFFEEEEEE, 0xFFEEEEEE, 0xFFEEEEEE, 0xFF000000,
                0xFF000000, 0xFFEEEEEE, 0xFFEEEEEE, 0xFFEEEEEE, 0xFF000000,
                0xFF000000, 0xFFEEEEEE, 0xFFEEEEEE, 0xFFEEEEEE, 0xFF000000
        });

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 5, 3, Palette.InkMode.MATTE, 255);

        assertEquals(0xFFEEEEEE, dest.getPixel(2, 1));
    }

    @Test
    void matteCopyOfWhiteBackedColoredTextIntoEightBitImageBuildsLumaMask() {
        Bitmap mask = new Bitmap(3, 3, 8, new int[] {
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF
        });
        Bitmap text = new Bitmap(3, 3, 32, new int[] {
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFF336666, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF
        });

        Drawing.copyPixels(mask, text, 0, 0, 0, 0, 3, 3, Palette.InkMode.MATTE, 255);

        assertEquals(0xFFFFFFFF, mask.getPixel(0, 0));
        assertEquals(0xFF575757, mask.getPixel(1, 1));
        assertEquals(0xFFFFFFFF, mask.getPixel(2, 2));
    }

    @Test
    void matteCopyIntoBlackEightBitImagePreservesWhiteTextPixels() {
        Bitmap dest = new Bitmap(3, 3, 8, new int[] {
                0xFF000000, 0xFF000000, 0xFF000000,
                0xFF000000, 0xFF000000, 0xFF000000,
                0xFF000000, 0xFF000000, 0xFF000000
        });
        Bitmap text = new Bitmap(3, 3, 32, new int[] {
                0xFF000000, 0xFF000000, 0xFF000000,
                0xFF000000, 0xFFFFFFFF, 0xFF000000,
                0xFF000000, 0xFF000000, 0xFF000000
        });

        Drawing.copyPixels(dest, text, 0, 0, 0, 0, 3, 3, Palette.InkMode.MATTE, 255);

        assertEquals(0xFF000000, dest.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, dest.getPixel(1, 1));
        assertEquals(0xFF000000, dest.getPixel(2, 2));
    }

    private static Bitmap blackOutlinedFlagBitmap() {
        return new Bitmap(5, 5, 32, new int[] {
                0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000,
                0xFF000000, 0xFFFF66AA, 0xFFFF66AA, 0xFFFF66AA, 0xFF000000,
                0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000,
                0xFF000000, 0xFFFF66AA, 0xFFFF66AA, 0xFFFF66AA, 0xFF000000,
                0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000
        });
    }

    @Test
    void backgroundTransparentCopyPixelsOnlyKeysExactBackgroundColor() {
        Bitmap dest = new Bitmap(3, 1, 32, new int[] {
                0xFF112233, 0xFF112233, 0xFF112233
        });
        Bitmap src = new Bitmap(3, 1, 32, new int[] {
                0xFFFFFFFF, 0xFFF0F0F0, 0xFF000000
        });

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 3, 1,
                Palette.InkMode.BACKGROUND_TRANSPARENT, 255, null, 0xFFFFFF);

        assertEquals(0xFF112233, dest.getPixel(0, 0));
        assertEquals(0xFFF0F0F0, dest.getPixel(1, 0));
        assertEquals(0xFF000000, dest.getPixel(2, 0));
    }
}
