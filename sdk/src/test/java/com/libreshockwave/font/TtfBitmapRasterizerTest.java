package com.libreshockwave.font;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtfBitmapRasterizerTest {

    @Test
    void rasterizedGlyphsPreserveLeftSideBearingAndAdvanceWidth() {
        BitmapFont font = TtfBitmapRasterizer.rasterize(
                com.libreshockwave.fonts.windows.Verdana.getData(), 9, "Verdana");

        int canvasW = 32;
        int canvasH = 32;
        int[] pixels = new int[canvasW * canvasH];
        font.drawChar('H', pixels, canvasW, canvasH, 0, 0, 0xFF000000);

        int minX = canvasW;
        int maxX = -1;
        for (int y = 0; y < canvasH; y++) {
            for (int x = 0; x < canvasW; x++) {
                if (((pixels[y * canvasW + x] >>> 24) & 0xFF) != 0) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
            }
        }

        int inkWidth = maxX - minX + 1;
        assertTrue(maxX >= 0, "glyph should render visible pixels");
        assertTrue(minX > 0, "glyph should preserve its left-side bearing");
        assertTrue(font.getCharWidth('H') > inkWidth,
                "advance width should preserve the font metrics, not collapse to the ink bounds");
    }

    @Test
    void bitmapFontMapsDirectorControlBytesThroughWindows1252Glyphs() {
        BitmapFont font = TtfBitmapRasterizer.rasterize(
                com.libreshockwave.fonts.volter.volter_bold.getData(), 9, "Volter");

        assertTrue(font.canDraw(0x0192), "fixture font should contain Windows-1252 glyph U+0192");
        assertTrue(font.canDraw(0x83), "raw Director byte 0x83 should resolve to U+0192");
        assertEquals(font.getCharWidth(0x0192), font.getCharWidth(0x83));
        assertArrayEquals(renderGlyphPixels(font, (char) 0x0192), renderGlyphPixels(font, (char) 0x83));
    }

    @Test
    void repeatedFractionalAdvancesAccumulateBeforePixelRounding() {
        BitmapFont font = TtfBitmapRasterizer.rasterize(
                com.libreshockwave.fonts.windows.Verdana.getData(), 9, "Verdana");

        char fractional = 0;
        for (char ch = 32; ch < 127; ch++) {
            int advanceFixed = font.getCharAdvanceFixed(ch);
            if ((advanceFixed & 0x3F) != 0) {
                fractional = ch;
                break;
            }
        }
        assertTrue(fractional != 0, "Verdana fixture should expose at least one fractional advance at 9px");

        String repeated = String.valueOf(fractional).repeat(20);
        int expected = BitmapFont.roundFixedToPixel(font.getCharAdvanceFixed(fractional) * 20);
        int perGlyphRounded = font.getCharWidth(fractional) * 20;

        assertEquals(expected, font.getStringWidth(repeated),
                "layout should accumulate logical advances and round the final pen position");
        assertTrue(expected != perGlyphRounded,
                "test fixture should distinguish accumulated rounding from per-glyph advance rounding");
    }

    @Test
    void kerningUsesFontTableOnlyWhenThresholdAllowsIt() {
        BitmapFont font = TtfBitmapRasterizer.rasterize(
                com.libreshockwave.fonts.windows.Verdana.getData(), 18, "Verdana");

        String pair = null;
        for (char left = 32; left < 127 && pair == null; left++) {
            for (char right = 32; right < 127; right++) {
                if (font.getKerningFixed(left, right) != 0) {
                    pair = "" + left + right;
                    break;
                }
            }
        }
        assertTrue(pair != null, "Verdana fixture should expose at least one ASCII kerning pair");

        int unkerned = font.getStringWidth(pair, false, 0);
        int thresholdDisabled = font.getStringWidth(pair, true, 99);
        int thresholdEnabled = font.getStringWidth(pair, true, 14);

        assertEquals(unkerned, thresholdDisabled,
                "kerningThreshold should block kerning below the configured point size");
        assertTrue(thresholdEnabled != unkerned,
                "enabled kerning should apply the font's kern-table adjustment");
    }

    @Test
    void missingGlyphFallbackCanBeDisabledForEmbeddedSubsetFonts() {
        BitmapFont defaultFont = TtfBitmapRasterizer.rasterize(
                com.libreshockwave.fonts.volter.volter_bold.getData(), 9, "Volter");
        BitmapFont embeddedSubsetFont = TtfBitmapRasterizer.rasterizeEmbeddedPfr(
                com.libreshockwave.fonts.volter.volter_bold.getData(), 9, 9, "Volter");

        assertTrue(defaultFont.allowsMissingGlyphFallback(),
                "standalone/system rasterization should keep missing-glyph fallback enabled");
        assertFalse(embeddedSubsetFont.allowsMissingGlyphFallback(),
                "embedded PFR subsets should not fall back to a system font for a missing glyph");
    }

    @Test
    void explicitHorizontalScaleCondensesAdvancesWithoutShrinkingLineMetrics() {
        BitmapFont full = TtfBitmapRasterizer.rasterize(
                com.libreshockwave.fonts.volter.volter_bold.getData(), 9, "Volter");
        BitmapFont condensed = TtfBitmapRasterizer.rasterize(
                com.libreshockwave.fonts.volter.volter_bold.getData(), 9, 9, "Volter", 7.0f / 9.0f);

        assertTrue(condensed.getStringWidth("Archive #5 - Grand Palace - MY PALACE 20")
                        < full.getStringWidth("Archive #5 - Grand Palace - MY PALACE 20"),
                "explicit horizontal scaling should condense text advances");
        assertEquals(full.getLineHeight(), condensed.getLineHeight(),
                "explicit horizontal scaling must not shrink the vertical glyph metrics");
        assertEquals(full.getFontSize(), condensed.getFontSize(),
                "explicit horizontal scaling must keep the reported font size");
    }

    @Test
    void explicitlyScaledPixelFontKeepsOnePixelVerticalStems() {
        BitmapFont condensed = TtfBitmapRasterizer.rasterize(
                com.libreshockwave.fonts.volter.volter_bold.getData(), 9, 9, "Volter", 7.0f / 9.0f);

        int[] pixels = renderGlyphPixels(condensed, 'm');
        int rowsWithInk = 0;
        int rowsWithMultipleStems = 0;
        int totalInk = 0;
        for (int y = 0; y < 32; y++) {
            int columns = 0;
            for (int x = 0; x < 32; x++) {
                if (((pixels[y * 32 + x] >>> 24) & 0xFF) != 0) {
                    columns++;
                    totalInk++;
                }
            }
            if (columns > 0) {
                rowsWithInk++;
            }
            if (columns >= 3) {
                rowsWithMultipleStems++;
            }
        }

        assertTrue(rowsWithInk >= 5,
                "condensed glyph should keep full-height vertical strokes, rows=" + rowsWithInk);
        assertTrue(rowsWithMultipleStems >= 5,
                "condensed m should not lose one-pixel stems, rows=" + rowsWithMultipleStems);
        assertTrue(totalInk >= 25,
                "condensed glyph should preserve visible stem pixels, ink=" + totalInk);
    }

    @Test
    void explicitlyScaledGlyphsThatOverrunAdvanceExtendLogicalWidth() {
        BitmapFont condensed = TtfBitmapRasterizer.rasterize(
                com.libreshockwave.fonts.volter.volter_bold.getData(), 9, 9, "Volter", 7.0f / 9.0f);

        for (char ch : "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray()) {
            int right = rightmostInkFromPen(condensed, ch, 2);
            int advance = condensed.getCharWidth(ch);
            if (right >= 0 && advance >= 4) {
                assertTrue(right < advance,
                        "condensed glyph ink must not overrun the next glyph pen position, ch="
                                + ch + " right=" + right + " advance=" + advance);
            }
        }
    }

    private static int[] renderGlyphPixels(BitmapFont font, char ch) {
        int canvasW = 32;
        int canvasH = 32;
        int[] pixels = new int[canvasW * canvasH];
        font.drawChar(ch, pixels, canvasW, canvasH, 1, 1, 0xFF000000);
        return pixels;
    }

    private static int rightmostInkFromPen(BitmapFont font, char ch, int penX) {
        int canvasW = 32;
        int canvasH = 32;
        int[] pixels = new int[canvasW * canvasH];
        font.drawChar(ch, pixels, canvasW, canvasH, penX, 1, 0xFF000000);
        int maxX = -1;
        for (int y = 0; y < canvasH; y++) {
            for (int x = 0; x < canvasW; x++) {
                if (((pixels[y * canvasW + x] >>> 24) & 0xFF) != 0) {
                    maxX = Math.max(maxX, x - penX);
                }
            }
        }
        return maxX;
    }
}
