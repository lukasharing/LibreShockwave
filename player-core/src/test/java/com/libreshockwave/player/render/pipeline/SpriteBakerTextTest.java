package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.render.output.SimpleTextRenderer;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.opcode.dispatch.ImageMethodDispatcher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
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
    void dynamicTextWithoutMemberColorUsesSpriteForeColor() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 64, 16));
        member.setProp("text", Datum.of("0/1"));

        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 64, 16, 0, true,
                RenderSprite.SpriteType.TEXT,
                null, member,
                0xFCFCFC, 0x000055,
                true, true,
                0, 100,
                false, false,
                null, false);

        Bitmap baked = new SpriteBaker(new BitmapCache(), null, null)
                .bake(sprite)
                .getBakedBitmap();

        assertTrue(countPixels(baked, 0xFFFCFCFC) > 0,
                "runtime text with no member color should inherit the sprite foreColor");
    }

    @Test
    void dynamicTextExplicitMemberColorOverridesSpriteForeColor() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 64, 16));
        member.setProp("text", Datum.of("0/1"));
        member.setProp("color", new Datum.Color(0, 0, 0));

        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 64, 16, 0, true,
                RenderSprite.SpriteType.TEXT,
                null, member,
                0xFCFCFC, 0x000055,
                true, true,
                0, 100,
                false, false,
                null, false);

        Bitmap baked = new SpriteBaker(new BitmapCache(), null, null)
                .bake(sprite)
                .getBakedBitmap();

        assertEquals(0, countPixels(baked, 0xFFFCFCFC));
        assertTrue(countPixels(baked, 0xFF000000) > 0,
                "member.color should remain stronger than sprite foreColor");
    }

    @Test
    void dynamicTextRenderCacheIncludesInheritedSpriteForeColor() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 64, 16));
        member.setProp("text", Datum.of("0/1"));

        SpriteBaker baker = new SpriteBaker(new BitmapCache(), null, null);
        RenderSprite whiteSprite = new RenderSprite(
                1, 0, 0, 64, 16, 0, true,
                RenderSprite.SpriteType.TEXT,
                null, member,
                0xFCFCFC, 0x000055,
                true, true,
                0, 100,
                false, false,
                null, false);
        RenderSprite redSprite = new RenderSprite(
                2, 0, 0, 64, 16, 0, true,
                RenderSprite.SpriteType.TEXT,
                null, member,
                0xFF0000, 0x000055,
                true, true,
                0, 100,
                false, false,
                null, false);

        Bitmap white = baker.bake(whiteSprite).getBakedBitmap();
        Bitmap red = baker.bake(redSprite).getBakedBitmap();

        assertTrue(countPixels(white, 0xFFFCFCFC) > 0);
        assertTrue(countPixels(red, 0xFFFF0000) > 0,
                "the cached runtime text image must be keyed by effective text color");
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
    void backgroundTransparentTextKeepsAuthoredNonWhiteBacking() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("vb"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fontstyle", Datum.of("plain"));
        member.setProp("alignment", Datum.symbol("center"));
        member.setProp("lineheight", Datum.of(11));
        member.setProp("wordwrap", Datum.of(0));
        member.setProp("boxtype", Datum.symbol("fixed"));
        member.setProp("rect", new Datum.Rect(0, 0, 186, 10));
        member.setProp("txtColor", Datum.of("#FCFCFC"));
        member.setProp("txtBgColor", Datum.of("#6A6A6A"));
        member.setProp("text", Datum.of("AVAILABLE LEVELS"));

        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 186, 10, 0, true,
                RenderSprite.SpriteType.TEXT,
                null, member,
                0xFCFCFC, 0x6A6A6A,
                true, true,
                36, 100,
                false, false,
                null, false);

        Bitmap baked = new SpriteBaker(new BitmapCache(), null, null)
                .bake(sprite)
                .getBakedBitmap();

        assertEquals(0xFF6A6A6A, baked.getPixel(0, 0),
                "ig_title_choose_lvl.window text should keep its authored dark backing under ink 36");
    }

    @Test
    void backgroundTransparentFileBackedTextKeepsRuntimePresentationBacking() throws Exception {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        setLoadedFileText(member, "AVAILABLE LEVELS");
        member.setProp("font", Datum.of("vb"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fontstyle", Datum.of("plain"));
        member.setProp("alignment", Datum.symbol("center"));
        member.setProp("lineheight", Datum.of(11));
        member.setProp("wordwrap", Datum.of(0));
        member.setProp("boxtype", Datum.symbol("fixed"));
        member.setProp("rect", new Datum.Rect(0, 0, 186, 10));
        member.setProp("txtColor", Datum.of("#FCFCFC"));
        member.setProp("txtBgColor", Datum.of("#6A6A6A"));

        assertFalse(member.hasDynamicText(), "The real window path mutates member props without replacing member.text");

        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 186, 10, 0, true,
                RenderSprite.SpriteType.TEXT,
                null, member,
                0xFCFCFC, 0x6A6A6A,
                true, true,
                36, 100,
                false, false,
                null, false);

        Bitmap baked = new SpriteBaker(new BitmapCache(), null, null)
                .bake(sprite)
                .getBakedBitmap();

        assertNotNull(baked);
        assertEquals(0xFF6A6A6A, baked.getPixel(0, 0));
        assertTrue(countPixels(baked, 0xFFFCFCFC) > 0,
                "runtime txtColor should draw the file-backed text with the authored white glyph color");
    }

    @Test
    void fileBackedWindowTextUsesRuntimeTextColorWithoutDynamicText() throws Exception {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        setLoadedFileText(member, "Introduce tu codigo");
        member.setProp("font", Datum.of("vb"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fontstyle", Datum.of("plain"));
        member.setProp("alignment", Datum.symbol("left"));
        member.setProp("lineheight", Datum.of(11));
        member.setProp("wordwrap", Datum.of(1));
        member.setProp("rect", new Datum.Rect(0, 0, 258, 14));
        member.setProp("txtColor", Datum.of("#333333"));

        assertFalse(member.hasDynamicText());

        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 258, 14, 0, true,
                RenderSprite.SpriteType.TEXT,
                null, member,
                0x333333, 0,
                true, false,
                36, 100,
                false, false,
                null, false);

        Bitmap baked = new SpriteBaker(new BitmapCache(), null, null)
                .bake(sprite)
                .getBakedBitmap();

        assertNotNull(baked);
        assertTrue(countPixels(baked, 0xFF333333) > 0,
                "PurseVouchers.window runtime txtColor should affect loaded field text even when member.text was not replaced");
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

    @Test
    void backgroundTransparentTextKeepsExplicitRgbSpriteBacking() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("vb"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 186, 10));
        member.setProp("text", Datum.of("AVAILABLE LEVELS"));

        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 186, 10, 0, true,
                RenderSprite.SpriteType.TEXT,
                null, member,
                0xFCFCFC, 0x6A6A6A,
                true, true,
                36, 100,
                false, false,
                null, false);

        Bitmap baked = new SpriteBaker(new BitmapCache(), null, null)
                .bake(sprite)
                .getBakedBitmap();

        assertEquals(0xFF6A6A6A, baked.getPixel(0, 0),
                "ink 36 should not discard a window-authored RGB text backing");
    }

    private static void setLoadedFileText(CastMember member, String text) throws Exception {
        Field textContent = CastMember.class.getDeclaredField("textContent");
        textContent.setAccessible(true);
        textContent.set(member, text);
        Field state = CastMember.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(member, CastMember.State.LOADED);
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
