package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.render.output.SimpleTextRenderer;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpriteBakerTextTest {

    @Test
    void dynamicTextUsesEffectiveSpriteBackColor() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("text", Datum.of("Title"));
        member.setProp("bgColor", Datum.of(0xEFEFEF));

        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 64, 16, 0, true,
                RenderSprite.SpriteType.TEXT,
                null, member,
                0xEEEEEE, 0x6794A7,
                true, false,
                0, 100,
                false, false,
                null, false);

        Bitmap baked = new SpriteBaker(new BitmapCache(), null, null)
                .bake(sprite)
                .getBakedBitmap();

        assertEquals(0xFF6794A7, baked.getPixel(0, 0));
    }

    @Test
    void backgroundTransparentTextUsesSpriteInkKeyAfterMemberRaster() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 64, 16));
        member.setProp("text", Datum.of("Title"));

        Bitmap memberImage = ((Datum.ImageRef) member.getProp("image")).bitmap();
        assertEquals(0xFFFFFFFF, sampleBackgroundPixel(memberImage),
                "member.image is the pre-compositing member surface with its default backing");

        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 64, 16, 0, true,
                RenderSprite.SpriteType.TEXT,
                null, member,
                0, 0,
                false, false,
                36, 100,
                false, false,
                null, false);

        Bitmap baked = new SpriteBaker(new BitmapCache(), null, null)
                .bake(sprite)
                .getBakedBitmap();

        assertEquals(0, (sampleBackgroundPixel(baked) >>> 24) & 0xFF,
                "backgroundTransparent belongs to sprite ink/compositing, not member.image alpha");
    }

    @Test
    void backgroundTransparentTextDoesNotLeakPaletteBackColorBacking() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 64, 16));
        member.setProp("text", Datum.of("Title"));

        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 64, 16, 0, true,
                RenderSprite.SpriteType.TEXT,
                null, member,
                0, 1,
                false, true,
                36, 100,
                false, false,
                null, false);

        Bitmap baked = new SpriteBaker(new BitmapCache(), null, null)
                .bake(sprite)
                .getBakedBitmap();

        assertEquals(0, (sampleBackgroundPixel(baked) >>> 24) & 0xFF,
                "palette-index backColor must not survive as an opaque text backing");
    }

    @Test
    void shiftBitmapDownPreservesSizeAndClearsLeadingRows() {
        Bitmap source = new Bitmap(3, 4, 32, new int[] {
                0xFF000001, 0xFF000002, 0xFF000003,
                0xFF000004, 0xFF000005, 0xFF000006,
                0xFF000007, 0xFF000008, 0xFF000009,
                0xFF00000A, 0xFF00000B, 0xFF00000C
        });
        source.setNativeAlpha(true);

        Bitmap shifted = SpriteBaker.shiftBitmapDown(source, 2, 0x00000000);

        assertEquals(3, shifted.getWidth());
        assertEquals(4, shifted.getHeight());
        assertEquals(0x00000000, shifted.getPixel(0, 0));
        assertEquals(0x00000000, shifted.getPixel(2, 1));
        assertEquals(0xFF000001, shifted.getPixel(0, 2));
        assertEquals(0xFF000006, shifted.getPixel(2, 3));
        assertTrue(shifted.isNativeAlpha());
    }

    private static int sampleBackgroundPixel(Bitmap bitmap) {
        return bitmap.getPixel(bitmap.getWidth() - 1, bitmap.getHeight() - 1);
    }
}
