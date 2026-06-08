package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageMethodDispatcherTextRerenderTest {

    @Test
    void backgroundTransparentCopyPixelsDoesNotClearPreviousDestinationPixels() {
        Bitmap oldText = transparentTextImage(8, 4, 1, 1);
        Bitmap newText = transparentTextImage(8, 4, 6, 2);
        Datum.PropList backgroundTransparent = new Datum.PropList();
        backgroundTransparent.add("ink", Datum.of(36), true);

        Bitmap reused = new Bitmap(8, 4, 32);
        reused.fill(0x00FFFFFF);
        copyFull(reused, oldText, backgroundTransparent);
        copyFull(reused, newText, backgroundTransparent);

        assertEquals(0xFF000000, reused.getPixel(1, 1),
                "copyPixels must not infer text lifecycle and erase prior destination pixels");
        assertEquals(0xFF000000, reused.getPixel(6, 2));
    }

    @Test
    void explicitFillBeforeBackgroundTransparentCopyPixelsClearsPreviousGlyphs() {
        Bitmap oldText = transparentTextImage(8, 4, 1, 1);
        Bitmap newText = transparentTextImage(8, 4, 6, 2);
        Datum.PropList backgroundTransparent = new Datum.PropList();
        backgroundTransparent.add("ink", Datum.of(36), true);

        Bitmap reused = new Bitmap(8, 4, 32);
        reused.fill(0x00FFFFFF);
        copyFull(reused, oldText, backgroundTransparent);
        reused.fill(0x00FFFFFF);
        copyFull(reused, newText, backgroundTransparent);

        assertEquals(0x00FFFFFF, reused.getPixel(1, 1),
                "clearing between text renders is the caller/member lifecycle's responsibility");
        assertEquals(0xFF000000, reused.getPixel(6, 2));
    }

    @Test
    void colorRemapRecolorsNativeAlphaTextMaskWithoutCopyingItsBackground() {
        Bitmap text = transparentTextImage(5, 3, 2, 1);
        Datum.PropList colorRemap = new Datum.PropList();
        colorRemap.add("color", new Datum.Color(254, 254, 254), true);

        Bitmap dest = new Bitmap(5, 3, 32);
        dest.fill(0xFF333333);
        copyFull(dest, text, colorRemap);

        assertEquals(0xFF333333, dest.getPixel(0, 0),
                "transparent text image background must remain transparent after #color remap");
        assertEquals(0xFFFEFEFE, dest.getPixel(2, 1),
                "copyPixels #color recolors the black text/mask pixels even when the source has alpha");
    }

    @Test
    void colorRemapTreatsWhiteBackedTextAsTransparentMask() {
        Bitmap text = new Bitmap(5, 3, 32);
        text.fill(0xFFFFFFFF);
        text.setPixel(2, 1, 0xFF000000);
        Datum.PropList colorRemap = new Datum.PropList();
        colorRemap.add("color", new Datum.Color(254, 254, 254), true);

        Bitmap dest = new Bitmap(5, 3, 32);
        dest.fill(0xFF777777);
        copyFull(dest, text, colorRemap);

        assertEquals(0xFF777777, dest.getPixel(0, 0),
                "copyPixels #color uses a white text backing as transparent mask background");
        assertEquals(0xFFFEFEFE, dest.getPixel(2, 1));
    }

    @Test
    void colorRemapDoesNotRecolorColoredNativeAlphaArt() {
        Bitmap art = new Bitmap(5, 3, 32);
        art.fill(0x00000000);
        art.setPixel(2, 1, 0xFFFF0000);
        art.setNativeAlpha(true);
        Datum.PropList colorRemap = new Datum.PropList();
        colorRemap.add("color", new Datum.Color(254, 254, 254), true);

        Bitmap dest = new Bitmap(5, 3, 32);
        dest.fill(0xFF333333);
        copyFull(dest, art, colorRemap);

        assertEquals(0xFF333333, dest.getPixel(0, 0));
        assertEquals(0xFFFF0000, dest.getPixel(2, 1),
                "the grayscale/mask guard must prevent #color from flattening colored alpha artwork");
    }

    private static Bitmap transparentTextImage(int width, int height, int glyphX, int glyphY) {
        Bitmap bitmap = new Bitmap(width, height, 32);
        bitmap.fill(0x00FFFFFF);
        bitmap.setPixel(glyphX, glyphY, 0xFF000000);
        bitmap.setNativeAlpha(true);
        bitmap.markTextRenderedImage(0x00FFFFFF);
        return bitmap;
    }

    private static void copyFull(Bitmap dest, Bitmap src, Datum.PropList props) {
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(
                        new Datum.ImageRef(src),
                        new Datum.Rect(0, 0, src.getWidth(), src.getHeight()),
                        new Datum.Rect(0, 0, src.getWidth(), src.getHeight()),
                        props));
    }
}
