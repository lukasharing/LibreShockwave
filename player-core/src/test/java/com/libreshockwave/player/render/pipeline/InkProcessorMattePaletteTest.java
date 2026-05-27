package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.id.InkMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InkProcessorMattePaletteTest {

    @Test
    void matteInkUsesPaletteIndexInsteadOfRgbForIndexedScriptBitmaps() {
        Bitmap src = new Bitmap(5, 5, 8);
        src.fill(0xFFFFFFFF);

        byte[] indices = new byte[25];
        // Border/background matte is palette index 0, but visible artwork can
        // share the same RGB white with a different index. Director keeps it
        // visible because the matte is indexed.
        indices[2 * 5 + 2] = 5;
        src.setPaletteIndices(indices);
        src.markScriptModified();

        Bitmap out = InkProcessor.applyInk(src, InkMode.MATTE, 0xFFFFFF, false, null);

        assertEquals(0, out.getPixel(0, 0) >>> 24);
        assertEquals(0xFFFFFFFF, out.getPixel(2, 2));
    }
}
