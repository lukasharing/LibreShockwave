package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.render.output.SimpleTextRenderer;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.opcode.dispatch.ImageMethodDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpriteBakerTextTest {

    @Test
    void scriptModifiedTextMemberImageIsUsedByBaker() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("rect", new Datum.Rect(0, 0, 16, 12));

        Datum.ImageRef image = (Datum.ImageRef) member.getProp("image");

        ImageMethodDispatcher.dispatch(image, "fill",
                List.of(new Datum.Rect(0, 0, 16, 12), new Datum.Color(255, 0, 0)));

        assertFalse(member.hasDynamicText());
        assertTrue(image.bitmap().isScriptModified());

        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 16, 12, 0, true,
                RenderSprite.SpriteType.TEXT,
                null, member,
                0, 0,
                false, false,
                0, 100,
                false, false,
                null, false);

        Bitmap baked = new SpriteBaker(new BitmapCache(), null, null)
                .bake(sprite)
                .getBakedBitmap();

        assertNotNull(baked);
        assertEquals(0xFFFF0000, baked.getPixel(4, 4));
    }

    @Test
    void unmodifiedTextMemberImageDoesNotExpandSpriteBounds() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("rect", new Datum.Rect(0, 0, 64, 12));
        member.setProp("text", Datum.of(""));

        assertNotNull(member.getProp("image"));

        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 16, 12, 0, true,
                RenderSprite.SpriteType.TEXT,
                null, member,
                0, 0,
                false, false,
                0, 100,
                false, false,
                null, false);

        RenderSprite baked = new SpriteBaker(new BitmapCache(), null, null).bake(sprite);

        assertNotNull(baked.getBakedBitmap());
        assertEquals(16, baked.getWidth());
        assertEquals(12, baked.getHeight());
    }

    @Test
    void dynamicTextUsesExplicitMemberBackgroundColor() {
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

        assertEquals(0xFFEFEFEF, baked.getPixel(0, 0));
    }

    @Test
    void dynamicTextFallsBackToEffectiveSpriteBackColor() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("text", Datum.of("Title"));

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
        assertEquals(0, (memberImage.getPixel(0, 0) >>> 24) & 0xFF,
                "member.image should not turn the default white bgColor into an opaque box");

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

        assertEquals(0, (baked.getPixel(0, 0) >>> 24) & 0xFF,
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

        assertEquals(0, (baked.getPixel(0, 0) >>> 24) & 0xFF,
                "palette-index backColor must not survive as an opaque text backing");
    }
}
