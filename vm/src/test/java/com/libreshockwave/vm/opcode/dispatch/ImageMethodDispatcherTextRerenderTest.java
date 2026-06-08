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
