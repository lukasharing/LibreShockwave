package com.libreshockwave.vm.builtin.media;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.builtin.cast.CastLibProvider;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImageBuiltinsTest {

    @Test
    void imageConstructorInitializesPaletteIndicesForPalettedImages() {
        Palette palette = new Palette(new int[] {0x000000, 0xFFFFFF}, "window");
        CastLibProvider.setProvider(new PaletteProvider(palette));
        try {
            Datum result = new LingoVM(null).callBuiltin("image",
                    List.of(Datum.of(2), Datum.of(1), Datum.of(8), Datum.CastMemberRef.of(1, 2)));

            Bitmap bitmap = assertInstanceOf(Datum.ImageRef.class, result).bitmap();
            assertSame(palette, bitmap.getImagePalette());
            assertEquals(0xFFFFFFFF, bitmap.getPixel(0, 0));
            assertArrayEquals(new byte[] {1, 1}, bitmap.getPaletteIndices(),
                    "image(w,h,8,palette) must create white through the palette, not raw RGB only");
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void imageConstructorCreatesCenteredAnchorForRuntimeBitmapMembers() {
        Datum result = new LingoVM(null).callBuiltin("image",
                List.of(Datum.of(42), Datum.of(60), Datum.of(8)));

        Bitmap bitmap = assertInstanceOf(Datum.ImageRef.class, result).bitmap();
        assertTrue(bitmap.hasAnchorPoint());
        assertEquals(21, bitmap.getAnchorX());
        assertEquals(30, bitmap.getAnchorY());
    }

    private record PaletteProvider(Palette palette) implements CastLibProvider {
        @Override public int getCastLibByNumber(int castLibNumber) { return castLibNumber; }
        @Override public int getCastLibByName(String name) { return -1; }
        @Override public Datum getCastLibProp(int castLibNumber, String propName) { return Datum.VOID; }
        @Override public boolean setCastLibProp(int castLibNumber, String propName, Datum value) { return false; }
        @Override public Datum getMember(int castLibNumber, int memberNumber) { return Datum.CastMemberRef.of(castLibNumber, memberNumber); }
        @Override public Datum getMemberByName(int castLibNumber, String memberName) { return Datum.VOID; }
        @Override public int getCastLibCount() { return 1; }
        @Override public Datum getMemberProp(int castLibNumber, int memberNumber, String propName) { return Datum.VOID; }
        @Override public boolean setMemberProp(int castLibNumber, int memberNumber, String propName, Datum value) { return false; }
        @Override public Palette getMemberPalette(int castLibNumber, int memberNumber) {
            return castLibNumber == 1 && memberNumber == 2 ? palette : null;
        }
    }
}
