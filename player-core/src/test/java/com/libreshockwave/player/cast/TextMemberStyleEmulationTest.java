package com.libreshockwave.player.cast;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.player.render.output.AwtTextRenderer;
import com.libreshockwave.player.render.output.SimpleTextRenderer;
import com.libreshockwave.player.render.output.TextRenderer;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextMemberStyleEmulationTest {

    @Test
    void memberChunkMethodReturnsStyledRangeReference() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = textMember("1", 31, 14);

        Datum range = member.callMethod("char", List.of(Datum.of(1), Datum.of(1)));

        assertTrue(range instanceof Datum.TextMemberRangeRef);
        Datum.TextMemberRangeRef ref = (Datum.TextMemberRangeRef) range;
        assertTrue(member.setTextRangeProp(ref.chunkType(), ref.start(), ref.end(),
                "color", new Datum.Color(238, 238, 238)));
        assertTrue(countPixels(member.renderTextToImage(), 0xFFEEEEEE) > 0);
    }

    @Test
    void partialCharRangeColorRendersOnlyRequestedCharacters() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = textMember("12", 54, 18);
        member.setProp("color", new Datum.Color(0, 0, 0));

        assertTrue(member.setTextRangeProp("char", 1, 1, "color", Datum.of("#EEEEEE")));

        Bitmap image = ((Datum.ImageRef) member.getProp("image")).bitmap();
        assertEquals(new Datum.Color(0xEE, 0xEE, 0xEE), member.getProp("color"));
        assertTrue(countPixels(image, 0xFFEEEEEE) > 0);
        assertTrue(countPixels(image, 0xFF000000) > 0);
    }

    @Test
    void memberColorGetterUsesFirstCharacterStyle() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = textMember("12", 54, 18);
        member.setProp("color", new Datum.Color(0, 0, 0));

        assertTrue(member.setTextRangeProp("char", 2, 2, "color", Datum.of("#EEEEEE")));

        Bitmap image = ((Datum.ImageRef) member.getProp("image")).bitmap();
        assertEquals(new Datum.Color(0, 0, 0), member.getProp("color"));
        assertTrue(countPixels(image, 0xFFEEEEEE) > 0);
        assertTrue(countPixels(image, 0xFF000000) > 0);
    }

    @Test
    void textResetClearsPreviousColorRanges() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = textMember("1", 54, 18);
        member.setProp("color", new Datum.Color(0, 0, 0));
        assertTrue(member.setTextRangeProp("char", 1, 1, "color", Datum.of("#EEEEEE")));
        assertTrue(countPixels(((Datum.ImageRef) member.getProp("image")).bitmap(), 0xFFEEEEEE) > 0);

        member.setProp("text", Datum.of("2"));
        member.setProp("color", new Datum.Color(0, 0, 0));

        Bitmap image = ((Datum.ImageRef) member.getProp("image")).bitmap();
        assertEquals(0, countPixels(image, 0xFFEEEEEE));
        assertTrue(countPixels(image, 0xFF000000) > 0);
    }

    @Test
    void voidBgColorClearsExplicitTextBackground() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = textMember("Label", 80, 11);
        member.setProp("bgcolor", new Datum.Color(0, 0, 255));
        member.setProp("bgcolor", Datum.VOID);

        Bitmap image = ((Datum.ImageRef) member.getProp("image")).bitmap();

        assertEquals(0, (image.getPixel(0, 0) >>> 24) & 0xFF);
        assertTrue(countPixels(image, 0xFF000000) > 0);
    }

    @Test
    void memberImageDoesNotReuseSpriteTextRenderBackground() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = textMember("Status message", 157, 33);
        member.setProp("color", new Datum.Color(238, 238, 238));

        Bitmap spriteTextImage = member.renderTextToImage(157, 33, 0xFF000055);
        assertTrue(countPixels(spriteTextImage, 0xFF000055) > 0);

        Bitmap memberImage = ((Datum.ImageRef) member.getProp("image")).bitmap();

        assertEquals(0, countPixels(memberImage, 0xFF000055));
        assertTrue(countPixels(memberImage, 0xFFEEEEEE) > 0);
        assertEquals(0, (memberImage.getPixel(0, 0) >>> 24) & 0xFF);
    }

    @Test
    void awtTextRendererHonorsColorRuns() {
        AwtTextRenderer renderer = new AwtTextRenderer();
        Bitmap image = renderer.renderText(
                "12", 54, 18,
                "Dialog", 12, "plain",
                "left", 0xFF000000, 0x00FFFFFF,
                false, false, 14, 0,
                false, 0,
                List.of(new TextRenderer.ColorRun(0, 1, 0xFFEEEEEE)));

        assertTrue(countPixels(image, 0xFFEEEEEE) > 0);
        assertTrue(countPixels(image, 0xFF000000) > 0);
    }

    private static CastMember textMember(String text, int width, int height) {
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("rect", new Datum.Rect(0, 0, width, height));
        member.setProp("text", Datum.of(text));
        return member;
    }

    private static int countPixels(Bitmap bitmap, int argb) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (bitmap.getPixel(x, y) == argb) {
                    count++;
                }
            }
        }
        return count;
    }
}
