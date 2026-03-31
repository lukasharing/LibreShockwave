package com.libreshockwave.player.cast;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Drawing;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.player.render.output.SimpleTextRenderer;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastMemberTextImageTest {

    @Test
    void textMemberImageUsesTransparentBackgroundEvenWhenBgColorIsDark() {
        CastMember member = buildTextMember("Habbo Club");

        Bitmap image = member.renderTextToImage();

        assertEquals(0, (image.getPixel(0, 0) >>> 24) & 0xFF);
        assertEquals(0, (image.getPixel(image.getWidth() - 1, image.getHeight() - 1) >>> 24) & 0xFF);
        assertTrue(image.hasNativeMatteAlpha(), "text member image should expose alpha-backed transparency");
    }

    @Test
    void explicitTextRenderKeepsOpaqueBackgroundSeparateFromMemberImageCache() {
        CastMember member = buildTextMember("Habbo Club");

        Bitmap transparentMemberImage = member.renderTextToImage();
        Bitmap opaqueSpriteImage = member.renderTextToImage(
                transparentMemberImage.getWidth(),
                transparentMemberImage.getHeight(),
                0xFF000000);

        assertEquals(0, (transparentMemberImage.getPixel(0, 0) >>> 24) & 0xFF);
        assertEquals(0xFF000000, opaqueSpriteImage.getPixel(0, 0));
    }

    @Test
    void matteCompositePreservesWhiteTextFromTransparentMemberImage() {
        CastMember member = buildTextMember("Habbo Club\rqg");

        Bitmap textImage = member.renderTextToImage();
        Bitmap dest = new Bitmap(textImage.getWidth(), textImage.getHeight(), 32);
        dest.fill(0xFF000000);

        Drawing.copyPixels(dest, textImage, 0, 0, 0, 0,
                textImage.getWidth(), textImage.getHeight(),
                Palette.InkMode.MATTE, 255);

        assertEquals(countWhitePixels(textImage), countWhitePixels(dest),
                "MATTE copy should preserve all rendered white text pixels");
    }

    @Test
    void lineHeightPropertyControlsWindowFieldCharMetrics() {
        CastMember member = buildFieldMember("A\rB");
        member.setProp("lineHeight", Datum.of(14));

        Datum.Point point = assertInstanceOf(Datum.Point.class,
                member.callMethod("charPosToLoc", List.of(Datum.of(3))));

        assertEquals(14, point.y(),
                "expected the second line to start at the field lineHeight used by .window fields");
    }

    @Test
    void lineHeightPropertyAliasesFixedLineSpaceForWindowFields() {
        CastMember member = buildFieldMember("A\rB");

        member.setProp("lineHeight", Datum.of(14));

        assertEquals(14, member.getProp("fixedLineSpace").toInt());
        assertEquals(14, member.getProp("lineHeight").toInt());
    }

    @Test
    void lineHeightPropertySeparatesRenderedFieldLines() {
        CastMember member = buildFieldMember("Time:\rMethod:");
        member.setProp("lineHeight", Datum.of(14));

        Bitmap image = member.renderTextToImage(120, 32, 0xFF000000);

        assertTrue(findFirstOpaqueRowFrom(image, 12) >= 14,
                "expected the second rendered line to honor the field lineHeight instead of collapsing upward");
    }

    private static CastMember buildTextMember(String text) {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("Verdana"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 120, 18));
        member.setProp("color", new Datum.Color(255, 255, 255));
        member.setProp("bgcolor", new Datum.Color(0, 0, 0));
        member.setProp("text", Datum.of(text));
        return member;
    }

    private static CastMember buildFieldMember(String text) {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 2, MemberType.TEXT);
        member.setProp("font", Datum.of("Arial"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 120, 32));
        member.setProp("color", new Datum.Color(255, 255, 255));
        member.setProp("bgcolor", new Datum.Color(0, 0, 0));
        member.setProp("text", Datum.of(text));
        return member;
    }

    private static int countWhitePixels(Bitmap bitmap) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int pixel = bitmap.getPixel(x, y);
                if (((pixel >>> 24) & 0xFF) != 0 && (pixel & 0xFFFFFF) == 0xFFFFFF) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int findFirstOpaqueRowFrom(Bitmap bitmap, int startRow) {
        for (int y = Math.max(0, startRow); y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (((bitmap.getPixel(x, y) >>> 24) & 0xFF) != 0
                        && (bitmap.getPixel(x, y) & 0xFFFFFF) != 0x000000) {
                    return y;
                }
            }
        }
        return -1;
    }
}
