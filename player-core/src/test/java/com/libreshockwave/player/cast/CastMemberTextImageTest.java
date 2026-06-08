package com.libreshockwave.player.cast;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Drawing;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.id.InkMode;
import com.libreshockwave.player.render.output.AwtTextRenderer;
import com.libreshockwave.player.render.output.SimpleTextRenderer;
import com.libreshockwave.player.render.output.TextRenderer;
import com.libreshockwave.player.render.pipeline.BitmapCache;
import com.libreshockwave.vm.builtin.movie.MoviePropertyProvider;
import com.libreshockwave.vm.opcode.dispatch.ImageMethodDispatcher;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastMemberTextImageTest {

    @Test
    void textMemberImageUsesMemberBackgroundColor() {
        CastMember member = buildTextMember("Premium Club");

        Bitmap image = member.renderTextToImage();

        assertEquals(0xFF000000, image.getPixel(0, 0));
        assertEquals(0xFF000000, image.getPixel(image.getWidth() - 1, image.getHeight() - 1));
    }

    @Test
    void explicitTextRenderKeepsBackgroundSeparateFromMemberImageCache() {
        CastMember member = buildTextMember("Premium Club");

        Bitmap memberImage = member.renderTextToImage();
        Bitmap opaqueSpriteImage = member.renderTextToImage(
                memberImage.getWidth(),
                memberImage.getHeight(),
                0xFFFFFFFF);

        assertEquals(0xFF000000, memberImage.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, opaqueSpriteImage.getPixel(0, 0));
    }

    @Test
    void memberImageIgnoresPriorSpriteTextRenderBackgroundCache() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("rect", new Datum.Rect(0, 0, 157, 33));
        member.setProp("color", new Datum.Color(238, 238, 238));
        member.setProp("text", Datum.of("You naughty Habbo!"));

        Bitmap spriteTextImage = member.renderTextToImage(157, 33, 0xFF000055);
        assertTrue(countPixels(spriteTextImage, 0xFF000055) > 0,
                "the setup must cache a sprite-oriented render with a dark backing");

        Bitmap memberImage = ((Datum.ImageRef) member.getProp("image")).bitmap();

        assertEquals(0, countPixels(memberImage, 0xFF000055),
                "the image of a text member must not reuse a prior sprite backColor render");
        assertTrue(countPixels(memberImage, 0xFFEEEEEE) > 0,
                "the member image should preserve the text member glyph color");
        assertEquals(0, (memberImage.getPixel(0, 0) >>> 24) & 0xFF,
                "a text member without explicit bgColor should expose transparent backing");
    }

    @Test
    void runtimeTextMemberImageDoesNotInventOpaqueDefaultBackground() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("rect", new Datum.Rect(0, 0, 104, 11));
        member.setProp("text", Datum.of("Page 1"));

        Bitmap image = ((Datum.ImageRef) member.getProp("image")).bitmap();
        Bitmap dest = new Bitmap(image.getWidth(), image.getHeight(), 32);
        dest.fill(0xFFEDECCD);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(image),
                        new Datum.Rect(0, 0, image.getWidth(), image.getHeight()),
                        new Datum.Rect(0, 0, image.getWidth(), image.getHeight())));

        assertEquals(0, (image.getPixel(0, 0) >>> 24) & 0xFF,
                "member.image should not manufacture an opaque white box from the default bgColor");
        assertEquals(0xFFEDECCD, dest.getPixel(0, 0),
                "COPY from default member.image should preserve the existing panel backing");
        assertTrue(countPixels(dest, 0xFF000000) > 0,
                "the default member image should still copy the glyph ink");
    }

    @Test
    void runtimeTextMemberImageFreezesCurrentWriterPixels() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("color", new Datum.Color(238, 238, 238));
        member.setProp("rect", new Datum.Rect(0, 0, 124, 12));
        member.setProp("text", Datum.of("Mini-Bar"));

        Datum.ImageRef firstImage = (Datum.ImageRef) member.getProp("image");
        Bitmap firstBitmap = firstImage.bitmap();
        assertTrue(countPixels(firstBitmap, 0xFFEEEEEE) > 0,
                "writer image should contain the current infostand title pixels");

        member.setProp("text", Datum.EMPTY_STRING);

        assertTrue(countPixels(firstImage.bitmap(), 0xFFEEEEEE) > 0,
                "stored writer images must not turn blank when the writer member is reused");
        Datum.ImageRef secondImage = (Datum.ImageRef) member.getProp("image");
        assertFalse(countPixels(secondImage.bitmap(), 0xFFEEEEEE) > 0,
                "new member.image reads should reflect the current writer text");
    }

    @Test
    void textMemberChunkMethodReturnsStyledRangeReference() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 31, 14));
        member.setProp("text", Datum.of("1"));

        Datum range = member.callMethod("char", List.of(Datum.of(1), Datum.of(1)));

        assertTrue(range instanceof Datum.TextMemberRangeRef,
                "compiled member.char(start,end) calls must produce a styled text range reference");
        Datum.TextMemberRangeRef ref = (Datum.TextMemberRangeRef) range;
        assertTrue(member.setTextRangeProp(ref.chunkType(), ref.start(), ref.end(),
                "color", new Datum.Color(238, 238, 238)));
        Bitmap image = member.renderTextToImage();

        assertTrue(countPixels(image, 0xFFEEEEEE) > 0,
                "style assignments through member.char(start,end) must affect the rendered glyph color");
    }

    @Test
    void runtimeTextMemberImageHonorsExplicitBackgroundColor() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 80, 11));
        member.setProp("bgcolor", new Datum.Color(255, 255, 255));
        member.setProp("text", Datum.of("Label"));

        Bitmap image = ((Datum.ImageRef) member.getProp("image")).bitmap();

        assertEquals(0xFFFFFFFF, image.getPixel(0, 0),
                "an explicit bgColor remains an authored opaque text backing");
    }

    @Test
    void textMemberVoidBgColorClearsExplicitBackgroundFill() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 80, 11));
        member.setProp("bgcolor", new Datum.Color(0, 0, 255));
        member.setProp("bgcolor", Datum.VOID);
        member.setProp("text", Datum.of("Label"));

        Bitmap image = ((Datum.ImageRef) member.getProp("image")).bitmap();

        assertEquals(0, (image.getPixel(0, 0) >>> 24) & 0xFF,
                "VOID bgColor should render the text member without an invented backing fill");
        assertTrue(countPixels(image, 0xFF000000) > 0,
                "clearing the backing fill must not erase the glyph pixels");
    }

    @Test
    void runtimeTextMemberHonorsDirectorTxtColorAliases() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("rect", new Datum.Rect(0, 0, 160, 12));
        member.setProp("txtColor", Datum.of("#B5EF6B"));
        member.setProp("txtBgColor", Datum.of("#003300"));
        member.setProp("text", Datum.of("Choose the layout"));

        Bitmap image = ((Datum.ImageRef) member.getProp("image")).bitmap();

        assertEquals(new Datum.Color(0xB5, 0xEF, 0x6B), member.getProp("color"),
                "Director window #txtColor should update the text member foreground color");
        assertEquals(new Datum.Color(0x00, 0x33, 0x00), member.getProp("bgColor"),
                "Director window #txtBgColor should update the text member background color");
        assertTrue(countPixels(image, 0xFFB5EF6B) > 0,
                "rendered glyph pixels should use the authored Room-O-Matic lime color");
        assertEquals(0xFF003300, image.getPixel(0, 0),
                "rendered text member should use the authored txtBgColor backing");
    }

    @Test
    void textMemberPaletteAliasesBackgroundColor() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 80, 11));
        member.setProp("palette", new Datum.Color(0x67, 0x94, 0xA7));
        member.setProp("text", Datum.of("Name"));

        Bitmap image = ((Datum.ImageRef) member.getProp("image")).bitmap();

        assertEquals(new Datum.Color(0x67, 0x94, 0xA7), member.getProp("bgColor"));
        assertEquals(new Datum.Color(0x67, 0x94, 0xA7), member.getProp("palette"));
        assertEquals(0xFF6794A7, image.getPixel(0, 0),
                "Director text/field member palette is used by legacy field wrappers as the text backing");
    }

    @Test
    void newTextMemberDefaultsToDirectorAntiAliasThreshold() {
        CastLib castLib = new CastLib(1, null, null);
        CastMember field = castLib.createDynamicMember("field");
        CastMember text = castLib.createDynamicMember("text");

        assertEquals(0, field.getProp("antiAlias").toInt(),
                "classic field members do not enable Director text-member antialiasing by default");
        assertEquals(1, text.getProp("antiAlias").toInt(),
                "Director text members default antiAlias to true");
        assertEquals(14, text.getProp("antiAliasThreshold").toInt(),
                "Director text members default automatic antialiasing threshold to 14 pt");
    }

    @Test
    void textMemberKerningDefaultsToDirectorThresholdAndCanBeConfigured() {
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);

        assertEquals(1, member.getProp("kerning").toInt(),
                "Director text kerning should be enabled as a member property");
        assertEquals(14, member.getProp("kerningThreshold").toInt(),
                "Director automatic kerning defaults to the documented 14 point threshold");

        member.setProp("kerning", Datum.of(0));
        member.setProp("kerningThreshold", Datum.of(1));

        assertEquals(0, member.getProp("kerning").toInt());
        assertEquals(1, member.getProp("kerningThreshold").toInt());
    }

    @Test
    void fullCharRangeColorUpdatesRenderedText() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("rect", new Datum.Rect(0, 0, 54, 18));
        member.setProp("text", Datum.of("1"));
        member.setProp("color", new Datum.Color(0, 0, 0));

        assertTrue(member.setTextRangeProp("char", 1, 1, "color", Datum.of("#EEEEEE")));

        Bitmap image = ((Datum.ImageRef) member.getProp("image")).bitmap();
        assertEquals(new Datum.Color(0xEE, 0xEE, 0xEE), member.getProp("color"));
        assertTrue(countPixels(image, 0xFFEEEEEE) > 0,
                "BBCode-applied member.char[...] color should affect the rendered writer image");
    }

    @Test
    void partialCharRangeColorRendersOnlyRequestedCharacters() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("rect", new Datum.Rect(0, 0, 54, 18));
        member.setProp("text", Datum.of("12"));
        member.setProp("color", new Datum.Color(0, 0, 0));

        assertTrue(member.setTextRangeProp("char", 1, 1, "color", Datum.of("#EEEEEE")));

        Bitmap image = ((Datum.ImageRef) member.getProp("image")).bitmap();
        assertEquals(new Datum.Color(0xEE, 0xEE, 0xEE), member.getProp("color"),
                "Director member.color should derive from the first styled character");
        assertTrue(countPixels(image, 0xFFEEEEEE) > 0,
                "the styled range should render with the BBCode color");
        assertTrue(countPixels(image, 0xFF000000) > 0,
                "characters outside the styled range should keep the member base color");
    }

    @Test
    void textMemberColorGetterUsesFirstCharacterStyle() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("rect", new Datum.Rect(0, 0, 54, 18));
        member.setProp("text", Datum.of("12"));
        member.setProp("color", new Datum.Color(0, 0, 0));

        assertTrue(member.setTextRangeProp("char", 2, 2, "color", Datum.of("#EEEEEE")));

        Bitmap image = ((Datum.ImageRef) member.getProp("image")).bitmap();
        assertEquals(new Datum.Color(0, 0, 0), member.getProp("color"),
                "Director member.color should stay at the first character style");
        assertTrue(countPixels(image, 0xFFEEEEEE) > 0,
                "the second styled character should render with the range color");
        assertTrue(countPixels(image, 0xFF000000) > 0,
                "the first character should keep the member base color");
    }

    @Test
    void awtTextRendererHonorsDirectorColorRanges() {
        AwtTextRenderer renderer = new AwtTextRenderer();
        Bitmap image = renderer.renderText(
                "12", 54, 18,
                "Dialog", 12, "plain",
                "left", 0xFF000000, 0x00FFFFFF,
                false, false, 14, 0,
                false, 0,
                List.of(new TextRenderer.ColorRun(0, 1, 0xFFEEEEEE)));

        assertTrue(countPixels(image, 0xFFEEEEEE) > 0,
                "desktop text rendering should honor Director member.char[...] colors");
        assertTrue(countPixels(image, 0xFF000000) > 0,
                "characters outside a color range should keep the fallback color");
    }

    @Test
    void availableLevelsWindowFieldUsesDirectorTxtColors() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
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

        Bitmap image = ((Datum.ImageRef) member.getProp("image")).bitmap();
        Bitmap stage = new Bitmap(186, 10, 32);
        stage.fill(0xFF6A6A6A);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(stage), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(image),
                        new Datum.Rect(0, 0, 186, 10),
                        new Datum.Rect(0, 0, 186, 10),
                        propList("ink", Datum.of(36))));

        assertTrue(countPixels(image, 0xFFFCFCFC) > 0,
                "the authored ig_title_available_levels field should render white glyphs");
        assertTrue(countPixels(image, 0xFF6A6A6A) > 0,
                "the field member image should preserve the authored dark gray backing");
        assertEquals(0, countPixels(image, 0xFF000000),
                "window field rendering should not fall back to black text");
        assertTrue(countPixels(stage, 0xFFFCFCFC) > 0,
                "background-transparent sprite ink should preserve the white title glyphs");

        Bitmap windowBuffer = new Bitmap(186, 10, 32);
        windowBuffer.fill(0xFF222222);
        Datum.PropList windowTextParams = propList("ink", Datum.of(36));
        windowTextParams.add("color", new Datum.Color(0xFC, 0xFC, 0xFC), true);
        windowTextParams.add("bgColor", new Datum.Color(0x6A, 0x6A, 0x6A), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(windowBuffer), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(image),
                        new Datum.Rect(0, 0, 186, 10),
                        new Datum.Rect(0, 0, 186, 10),
                        windowTextParams));

        assertEquals(0xFF222222, windowBuffer.getPixel(0, 0),
                "ig_title_choose_lvl.window should key out the #txtBgColor backing during copyPixels");
        assertTrue(countPixels(windowBuffer, 0xFFFCFCFC) > 0,
                "white available-levels title glyphs should survive the keyed copy");
    }

    @Test
    void writerTextResetClearsPreviousBbcodeColorRanges() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("rect", new Datum.Rect(0, 0, 54, 18));
        member.setProp("text", Datum.of("1"));
        member.setProp("color", new Datum.Color(0, 0, 0));
        assertTrue(member.setTextRangeProp("char", 1, 1, "color", Datum.of("#EEEEEE")));
        assertTrue(countPixels(((Datum.ImageRef) member.getProp("image")).bitmap(), 0xFFEEEEEE) > 0);

        member.setProp("text", Datum.of("2"));
        member.setProp("color", new Datum.Color(0, 0, 0));

        Bitmap image = ((Datum.ImageRef) member.getProp("image")).bitmap();
        assertEquals(0, countPixels(image, 0xFFEEEEEE),
                "reused Writer_Class members should not leak old BBCode color runs into new text");
        assertTrue(countPixels(image, 0xFF000000) > 0,
                "new text should use the current member base color after Writer_Class resets it");
    }

    @Test
    void memberTextChangeInvalidatesScriptModifiedWriterCanvas() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("rect", new Datum.Rect(0, 0, 80, 18));
        member.setProp("text", Datum.of("Old"));

        Datum.ImageRef writerImage = (Datum.ImageRef) member.getProp("image");
        Bitmap redPatch = new Bitmap(4, 4, 32);
        redPatch.fill(0xFFFF0000);
        ImageMethodDispatcher.dispatch(writerImage, "copyPixels",
                List.of(
                        new Datum.ImageRef(redPatch),
                        new Datum.Rect(0, 0, 4, 4),
                        new Datum.Rect(0, 0, 4, 4)));

        assertTrue(countPixels(member.getScriptModifiedTextImage(), 0xFFFF0000) > 0,
                "mutating member.image should expose a script-modified writer canvas");

        member.setProp("text", Datum.of("New"));

        assertNull(member.getScriptModifiedTextImage(),
                "changing member.text must retire the previous script-modified text image");
        Bitmap rerendered = ((Datum.ImageRef) member.getProp("image")).bitmap();
        assertEquals(0, countPixels(rerendered, 0xFFFF0000),
                "the next member.image render must not contain pixels from the retired writer canvas");
        assertTrue(countPixels(rerendered, 0xFF000000) > 0,
                "the changed text should still render normally after invalidation");
    }

    @Test
    void textMemberRangeTextReadsRequestedChunk() {
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("text", Datum.of("Large TV\rSmall Chair"));

        assertEquals("Large TV", member.getTextRangeProp("line", 1, 1, "text").toStr());
        assertEquals("TV", member.getTextRangeProp("word", 2, 2, "text").toStr());
    }

    @Test
    void textMemberLineCountsUseDirectorChunkParsing() {
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("text", Datum.of("Black Two-Seater Sofa\r\nCushioned\u0002"));

        assertEquals(2, member.callMethod("count", java.util.List.of(Datum.symbol("line"))).toInt());
        assertEquals("Black Two-Seater Sofa",
                member.getTextRangeProp("line", 1, 1, "text").toStr());
        assertEquals("Cushioned",
                member.getTextRangeProp("line", 2, 2, "text").toStr());
    }

    @Test
    void textMemberItemsRespectGlobalReturnDelimiterForExternalTextRows() {
        char previousDelimiter = MoviePropertyProvider.ItemDelimiterCache._char;
        MoviePropertyProvider.ItemDelimiterCache._char = '\r';
        try {
            CastMember member = new CastMember(1, 10000, MemberType.TEXT);
            member.setProp("text", Datum.of("furni_sofa_silo2*2_name=Black Two-Seater Sofa\n"
                    + "furni_sofa_silo2*2_desc=Cushioned"));

            assertEquals(2, member.callMethod("count", java.util.List.of(Datum.symbol("item"))).toInt());
            assertEquals("furni_sofa_silo2*2_desc=Cushioned",
                    member.getTextRangeProp("item", 2, 2, "text").toStr());
        } finally {
            MoviePropertyProvider.ItemDelimiterCache._char = previousDelimiter;
        }
    }

    @Test
    void textMemberGetPropReturnsRangeReferenceForIndexedChunks() {
        CastMember member = new CastMember(1, 10000, MemberType.TEXT);
        member.setProp("text", Datum.of("Large TV\rSmall Chair"));

        Datum range = member.callMethod("getProp", java.util.List.of(Datum.symbol("line"), Datum.of(1)));

        assertTrue(range instanceof Datum.TextMemberRangeRef);
        Datum.TextMemberRangeRef ref = (Datum.TextMemberRangeRef) range;
        assertEquals(1, ref.castLibNum());
        assertEquals(10000, ref.memberNum());
        assertEquals("line", ref.chunkType());
        assertEquals(1, ref.start());
        assertEquals(1, ref.end());
        assertEquals("Large TV", member.getTextRangeProp(ref.chunkType(), ref.start(), ref.end(), "text").toStr());

        Datum charRange = member.callMethod("getPropRef",
                java.util.List.of(Datum.symbol("char"), Datum.of(1), Datum.of(1)));
        assertTrue(charRange instanceof Datum.TextMemberRangeRef);
        Datum.TextMemberRangeRef charRef = (Datum.TextMemberRangeRef) charRange;
        assertEquals("char", charRef.chunkType());
        assertEquals(1, charRef.start());
        assertEquals(1, charRef.end());
    }

    @Test
    void matteCompositePreservesWhiteTextFromBlackBackedMemberImage() {
        CastMember member = buildTextMember("Premium Club\rqg");

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
    void compactVFontWhiteTextRendersInsideTenPixelHighBottomBarFields() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(11));
        member.setProp("rect", new Datum.Rect(0, 0, 90, 10));
        member.setProp("color", new Datum.Color(255, 255, 255));
        member.setProp("bgcolor", new Datum.Color(0, 0, 0));
        member.setProp("text", Datum.of("Premium Club"));

        Bitmap textImage = member.renderTextToImage();

        assertTrue(countWhitePixels(textImage) > 0,
                "Bottom bar fields are only 10px high, so V/9 text must not be clipped away");
    }

    @Test
    void fieldLineHeightSetsTheEffectiveCompactLineAdvance() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(9));
        member.setProp("lineheight", Datum.of(18));
        member.setProp("rect", new Datum.Rect(0, 0, 120, 0));
        member.setProp("text", Datum.of("A\nB"));

        Bitmap textImage = member.renderTextToImage();
        Datum.Point secondLine = (Datum.Point) member.callMethod("charpostoloc", java.util.List.of(Datum.of(3)));

        assertEquals(18, member.getProp("lineheight").toInt(),
                "field lineHeight should be exposed as the active text line advance");
        assertEquals(18, member.getProp("fixedlinespace").toInt(),
                "field lineHeight must drive the renderer even when the natural font line box is taller");
        assertEquals(36, textImage.getHeight(),
                "two-line field text should use the scripted compact line advance");
        assertEquals(18, secondLine.y(),
                "charPosToLoc should place the textarea caret on the second scripted line");
    }

    @Test
    void compactUnderlinedFieldLinksKeepUnderlineInsideElevenPixelWindowRect() {
        CastMember.setTextRenderer(new SimpleTextRenderer());

        int expectedUnderlineRow = -1;
        for (String label : new String[]{"Hotel rules", "Terms and Conditions", "Privacy Pledge"}) {
            CastMember plain = compactTermsLink(label, "plain", 11);
            CastMember underlined = compactTermsLink(label, "underline", 11);

            Bitmap plainImage = underlinedTextImage(plain);
            Bitmap underlineImage = underlinedTextImage(underlined);
            int underlineRow = findAddedColorRow(plainImage, underlineImage, 0xFF990000);

            if (expectedUnderlineRow < 0) {
                expectedUnderlineRow = underlineRow;
            }
            assertTrue(underlineRow >= 0,
                    "expected visible underline in compact window field: " + label);
            assertEquals(expectedUnderlineRow, underlineRow,
                    "same font/lineHeight links should place the underline on a stable row: " + label);
            assertTrue(countColorPixelsOnRow(underlineImage, underlineRow, 0xFF990000) >= 40,
                    "expected the underline to survive clipping for: " + label);
        }
    }

    @Test
    void fontStyleNormalizesSymbolListsToDirectorStringForm() {
        CastMember member = new CastMember(1, 1, MemberType.TEXT);

        member.setProp("fontstyle", Datum.list(Datum.symbol("underline"), Datum.symbol("bold")));

        assertEquals("bold, underline", member.getProp("fontstyle").toStr(),
                "fontStyle should be exposed as the documented Director string form");
    }

    @Test
    void partialTextRangeBoldPromotesMemberStyleWhenRunsAreFlattened() {
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("text", Datum.of("name: message"));
        member.setProp("fontstyle", Datum.of("plain"));

        assertTrue(member.setTextRangeProp("char", 1, 4, "fontstyle",
                Datum.list(Datum.symbol("bold"))));

        assertEquals("bold", member.getProp("fontstyle").toStr(),
                "partial Director fontStyle ranges should preserve bold when rendered by the flat text path");
    }

    @Test
    void symbolicFixedBoxTypeKeepsAuthoredFieldHeight() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("wordwrap", Datum.of(1));
        member.setProp("rect", new Datum.Rect(0, 0, 80, 11));
        member.setProp("boxtype", Datum.symbol("fixed"));
        member.setProp("text", Datum.of("This text wraps onto several lines in an adjust field"));

        Bitmap fixed = member.renderTextToImage();

        member.setProp("boxtype", Datum.symbol("adjust"));
        Bitmap adjusted = member.renderTextToImage();

        assertEquals(11, fixed.getHeight(),
                "Director #fixed fields must not be coerced through Symbol.toInt() into #adjust");
        assertTrue(adjusted.getHeight() > fixed.getHeight(),
                "#adjust should still expand to fit wrapped content");
    }

    @Test
    void symbolicLimitBoxTypeKeepsAuthoredFieldHeight() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("wordwrap", Datum.of(1));
        member.setProp("rect", new Datum.Rect(0, 0, 80, 11));
        member.setProp("boxtype", Datum.symbol("limit"));
        member.setProp("text", Datum.of("This text wraps onto several lines in a limited field"));

        Bitmap limited = member.renderTextToImage();

        assertEquals(11, limited.getHeight(),
                "Director #limit fields render inside the authored box instead of expanding like #adjust");
    }

    @Test
    void fixedAndLimitBoxTypesClipHorizontalOverflowWithoutCondensing() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        SimpleTextRenderer renderer = new SimpleTextRenderer();
        String text = "HHHHHHHHHH";
        int fieldWidth = 24;
        int fieldHeight = 14;

        int[] afterEnd = renderer.charPosToLoc(text, text.length() + 1,
                "Verdana", 9, "plain",
                11, "left", 160);
        Bitmap natural = renderer.renderText(text, 160, fieldHeight,
                "Verdana", 9, "plain",
                "left", 0xFF000000, 0xFFFFFFFF,
                false, false, 11, 0);

        assertTrue(afterEnd[0] > fieldWidth,
                "test setup should create horizontal overflow before clipping");
        for (String boxType : new String[]{"fixed", "limit"}) {
            CastMember member = new CastMember(1, 1, MemberType.TEXT);
            member.setProp("font", Datum.of("Verdana"));
            member.setProp("fontsize", Datum.of(9));
            member.setProp("fixedlinespace", Datum.of(11));
            member.setProp("rect", new Datum.Rect(0, 0, fieldWidth, fieldHeight));
            member.setProp("boxtype", Datum.symbol(boxType));
            member.setProp("text", Datum.of(text));

            Bitmap clipped = member.renderTextToImage();

            assertEquals(fieldWidth, clipped.getWidth(),
                    "Director #" + boxType + " fields keep the authored width");
            assertEquals(fieldHeight, clipped.getHeight(),
                    "Director #" + boxType + " fields keep the authored height");
            assertLeftCropEquals(natural, clipped, boxType);
        }
    }

    @Test
    void adjustTextHeightDoesNotShrinkBelowScriptedRect() {
        CastMember member = buildTextMember("Oops.. Cannot connect to the service");
        member.setProp("rect", new Datum.Rect(0, 0, 220, 120));

        assertEquals(120, member.getProp("height").toInt(),
                "boxType=adjust should expand, not shrink below the scripted rect");
    }

    @Test
    void adjustTextImageKeepsScriptedRectHeightWhenItAlreadyFits() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("VB"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("topspacing", Datum.of(1));
        member.setProp("wordwrap", Datum.of(1));
        member.setProp("alignment", Datum.symbol("center"));
        member.setProp("fontstyle", Datum.of("underline"));
        member.setProp("rect", new Datum.Rect(0, 0, 176, 27));
        member.setProp("text", Datum.of("Need an account yet?\rYou can create one here."));

        Bitmap image = member.renderTextToImage();

        assertEquals(27, image.getHeight(),
                "adjust text images should preserve a fitting scripted rect to avoid wrapper copyPixels scaling");
    }

    @Test
    void adjustTextImageExpandsForScrollableWrappedText() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("Verdana"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(9));
        member.setProp("wordwrap", Datum.of(1));
        member.setProp("rect", new Datum.Rect(0, 0, 360, 180));
        member.setProp("text", Datum.of(repeatedScrollableText()));

        Bitmap image = member.renderTextToImage();
        Datum.Rect rect = (Datum.Rect) member.getProp("rect");
        int renderedHeight = rect.bottom() - rect.top();

        assertTrue(renderedHeight > 180,
                "boxType=adjust text must expose full content height for scrollbar source rects");
        assertEquals(renderedHeight, image.getHeight(),
                "member.image and member.rect must agree after auto-height expansion");
    }

    @Test
    void scrollableAgreementTextWrapperKeepsVisibleSliceReadable() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setName("visual window text");
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("topspacing", Datum.of(1));
        member.setProp("wordwrap", Datum.of(1));
        member.setProp("rect", new Datum.Rect(0, 0, 227, 154));
        member.setProp("color", new Datum.Color(0, 0, 0));
        member.setProp("bgcolor", new Datum.Color(255, 255, 255));
        member.setProp("text", Datum.of(sampleScrollableAgreementText()));

        Bitmap textImage = ((Datum.ImageRef) member.getProp("image")).bitmap();
        Datum.Rect textRect = (Datum.Rect) member.getProp("rect");
        int textRectHeight = textRect.bottom() - textRect.top();

        Bitmap visible = new Bitmap(227, 154, 32);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(visible), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(textImage),
                        new Datum.Rect(0, 0, 227, 154),
                        new Datum.Rect(0, 0, 227, 154),
                        propList("ink", Datum.of(8))));

        assertTrue(textRectHeight > 154,
                "scrollable terms text must expose a taller source rect than the visible viewport");
        assertEquals(textRectHeight, textImage.getHeight(),
                "text member image and rect must agree before Text Wrapper copies the source");
        assertTrue(countPixels(visible, 0xFF000000) < 9000,
                "visible terms slice should contain normal glyph density, not vertically smeared text");
    }

    @Test
    void scrollableAgreementRerenderClearsPreviousSliceBeforeCopyingOffset() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setName("visual window text");
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("topspacing", Datum.of(1));
        member.setProp("wordwrap", Datum.of(1));
        member.setProp("rect", new Datum.Rect(0, 0, 227, 154));
        member.setProp("color", new Datum.Color(0, 0, 0));
        member.setProp("bgcolor", new Datum.Color(255, 255, 255));
        member.setProp("text", Datum.of(sampleScrollableAgreementText()));

        Bitmap textImage = ((Datum.ImageRef) member.getProp("image")).bitmap();
        Datum.Rect textRect = (Datum.Rect) member.getProp("rect");
        int textHeight = textRect.bottom() - textRect.top();
        Bitmap wrapperImage = new Bitmap(227, textHeight, 32);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(wrapperImage), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(textImage),
                        new Datum.Rect(0, 0, 227, textHeight),
                        new Datum.Rect(0, 0, 227, textHeight),
                        propList("ink", Datum.of(8))));

        Bitmap buffer = new Bitmap(227, 154, 32);
        Datum.PropList backgroundTransparent = propList("ink", Datum.of(36));
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(buffer), "fill",
                java.util.List.of(new Datum.Rect(0, 0, 227, 154), new Datum.Color(255, 255, 255)));
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(buffer), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(wrapperImage),
                        new Datum.Rect(0, 0, 227, 154),
                        new Datum.Rect(0, 0, 227, 154),
                        backgroundTransparent));

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(buffer), "fill",
                java.util.List.of(new Datum.Rect(0, 0, 227, 154), new Datum.Color(255, 255, 255)));
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(buffer), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(wrapperImage),
                        new Datum.Rect(0, 0, 227, 154),
                        new Datum.Rect(0, 80, 227, 234),
                        backgroundTransparent));

        assertTrue(countPixels(buffer, 0xFF000000) < 9000,
                "scroll rerender must clear old glyphs before copying the newly offset visible slice");
        assertTrue(countWhitePixels(buffer) > 20000,
                "background-transparent text copies rely on the wrapper's white clear between scroll positions");
    }

    @Test
    void scrollableAgreementScrolledSliceMatchesFreshlyClearedRender() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(10));
        member.setProp("topspacing", Datum.of(1));
        member.setProp("wordwrap", Datum.of(1));
        member.setProp("rect", new Datum.Rect(0, 0, 227, 154));
        member.setProp("color", new Datum.Color(0, 0, 0));
        member.setProp("bgcolor", new Datum.Color(255, 255, 255));
        member.setProp("text", Datum.of(sampleScrollableAgreementText()));

        Bitmap textImage = ((Datum.ImageRef) member.getProp("image")).bitmap();
        int textHeight = ((Datum.Rect) member.getProp("rect")).height();
        Bitmap wrapperImage = new Bitmap(227, textHeight, 32);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(wrapperImage), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(textImage),
                        new Datum.Rect(0, 0, 227, textHeight),
                        new Datum.Rect(0, 0, 227, textHeight),
                        propList("ink", Datum.of(8))));

        Bitmap reused = new Bitmap(227, 154, 32);
        Bitmap fresh = new Bitmap(227, 154, 32);
        Datum.PropList backgroundTransparent = propList("ink", Datum.of(36));

        copyTermsSlice(reused, wrapperImage, 0, backgroundTransparent);
        fillWhite(reused);
        copyTermsSlice(reused, wrapperImage, 80, backgroundTransparent);

        fillWhite(fresh);
        copyTermsSlice(fresh, wrapperImage, 80, backgroundTransparent);

        assertArrayEquals(fresh.getPixels(), reused.getPixels(),
                "scrolling must clear the old visible text slice before drawing the new source offset");
    }

    @Test
    void runtimeWriterMeasurementRectReportsRenderedHeight() {
        CastMember member = buildTextMember("These are public rooms. What are you waiting for? Go and meet other users!");
        member.setName("writer_public_info");
        member.setProp("wordwrap", Datum.of(1));
        member.setProp("rect", new Datum.Rect(0, 0, 480, 480));

        Bitmap rendered = member.renderTextToImage();

        assertEquals(rendered.getHeight(), member.getProp("height").toInt(),
                "Temporary writer measurement rects should not make fake-alpha text images 480px tall");
    }

    @Test
    void rightAlignedCourierLinkStyleRendersVisibleTealUnderline() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("Courier"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fontstyle", Datum.of("underline"));
        member.setProp("alignment", Datum.symbol("right"));
        member.setProp("fixedlinespace", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 165, 14));
        member.setProp("color", new Datum.Color(0x7B, 0x94, 0x98));
        member.setProp("bgcolor", new Datum.Color(0xFF, 0xFF, 0xFF));
        member.setProp("text", Datum.of("Hide Full Rooms"));

        Bitmap textImage = member.renderTextToImage();
        int teal = 0xFF7B9498;

        int firstTealColumn = findFirstColorColumn(textImage, teal);
        int lastTealRow = findLastColorRow(textImage, teal);

        assertTrue(countPixels(textImage, teal) > 0,
                "expected rendered teal link pixels for Hide Full Rooms");
        assertTrue(firstTealColumn > 0,
                "expected right-aligned link ink to leave some leading whitespace");
        assertTrue(findLastColorColumn(textImage, teal) >= textImage.getWidth() - 6,
                "expected right-aligned link ink to reach the right edge of the field");
        assertTrue(lastTealRow >= 0 && countColorPixelsOnRow(textImage, lastTealRow, teal) >= 20,
                "expected a visible underline row in the teal link image");
    }

    @Test
    void textMemberImagePreservesMemberColorsForDirectWindowWriters() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fontstyle", Datum.of("bold"));
        member.setProp("alignment", Datum.symbol("center"));
        member.setProp("fixedlinespace", Datum.of(11));
        member.setProp("rect", new Datum.Rect(0, 0, 160, 18));
        member.setProp("color", new Datum.Color(0xFF, 0xFF, 0xFF));
        member.setProp("bgcolor", new Datum.Color(0x6B, 0x9E, 0xAE));
        member.setProp("text", Datum.of("First time here?"));

        Datum imageDatum = member.getProp("image");
        Bitmap image = ((Datum.ImageRef) imageDatum).bitmap();

        assertTrue(countPixels(image, 0xFFFFFFFF) > 0,
                "text member .image should preserve white header glyph pixels");
        assertTrue(countPixels(image, 0xFF6B9EAE) > 0,
                "text member .image should preserve the scripted blue backing");
        assertEquals(0, countPixels(image, 0xFF000000),
                "direct window writers should not receive forced black glyph ink");
    }

    @Test
    void explicitWindowBufferFillBeforeTextRerenderMatchesFreshBuffer() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("VB"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fontstyle", Datum.of("underline"));
        member.setProp("alignment", Datum.symbol("center"));
        member.setProp("fixedlinespace", Datum.of(9));
        member.setProp("wordwrap", Datum.of(1));
        member.setProp("rect", new Datum.Rect(0, 0, 176, 27));
        member.setProp("color", new Datum.Color(0, 0, 0));
        member.setProp("bgcolor", new Datum.Color(255, 255, 255));
        member.setProp("text", Datum.of("login_createUser"));

        Bitmap placeholder = ((Datum.ImageRef) member.getProp("image")).bitmap();
        Datum.PropList backgroundTransparent = propList("ink", Datum.of(36));
        Bitmap reused = new Bitmap(176, 27, 32);
        reused.fill(0xFFFFFFFF);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(reused), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(placeholder),
                        new Datum.Rect(0, 0, 176, 27),
                        new Datum.Rect(0, 0, 176, 27),
                        backgroundTransparent));

        member.setProp("text", Datum.of("You can create one here."));
        Bitmap localized = ((Datum.ImageRef) member.getProp("image")).bitmap();
        reused.fill(0xFFFFFFFF);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(reused), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(localized),
                        new Datum.Rect(0, 0, 176, 27),
                        new Datum.Rect(0, 0, 176, 27),
                        backgroundTransparent));

        Bitmap fresh = new Bitmap(176, 27, 32);
        fresh.fill(0xFFFFFFFF);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(fresh), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(localized),
                        new Datum.Rect(0, 0, 176, 27),
                        new Datum.Rect(0, 0, 176, 27),
                        backgroundTransparent));

        assertArrayEquals(fresh.getPixels(), reused.getPixels(),
                "window text rerender clearing belongs to the caller-owned buffer lifecycle");
    }

    @Test
    void textWrapperNegativePaddingRerenderClearsPreviousGlyphs() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("VB"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fontstyle", Datum.of("underline"));
        member.setProp("alignment", Datum.symbol("center"));
        member.setProp("fixedlinespace", Datum.of(9));
        member.setProp("wordwrap", Datum.of(1));
        member.setProp("rect", new Datum.Rect(0, 0, 176, 27));
        member.setProp("color", new Datum.Color(0, 0, 0));
        member.setProp("bgcolor", new Datum.Color(255, 255, 255));
        member.setProp("text", Datum.of("You can create one here."));

        Bitmap textImage = ((Datum.ImageRef) member.getProp("image")).bitmap();
        assertTrue(textImage.isTextRenderedImage(),
                "member.image should carry text-render metadata");
        Bitmap wrapperImage = new Bitmap(176, 31, 32);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(wrapperImage), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(textImage),
                        new Datum.Rect(0, 2, 176, 29),
                        new Datum.Rect(0, 0, 176, 27),
                        propList("ink", Datum.of(8))));
        assertTrue(wrapperImage.isTextRenderedImage(),
                "Text Wrapper intermediates should preserve text-render metadata for later clears");

        Datum.PropList backgroundTransparent = propList("ink", Datum.of(36));
        Bitmap reused = new Bitmap(176, 27, 32);
        reused.fill(0xFFFFFFFF);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(reused), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(textImage),
                        new Datum.Rect(0, -2, 176, 25),
                        new Datum.Rect(0, 0, 176, 27),
                        backgroundTransparent));
        reused.fill(0xFFFFFFFF);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(reused), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(wrapperImage),
                        new Datum.Rect(0, -2, 176, 29),
                        new Datum.Rect(0, 0, 176, 31),
                        backgroundTransparent));

        Bitmap fresh = new Bitmap(176, 27, 32);
        fresh.fill(0xFFFFFFFF);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(fresh), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(wrapperImage),
                        new Datum.Rect(0, -2, 176, 29),
                        new Datum.Rect(0, 0, 176, 31),
                        backgroundTransparent));

        assertArrayEquals(fresh.getPixels(), reused.getPixels(),
                "Text Wrapper rerenders match fresh output when the caller clears the target buffer");
    }

    @Test
    void coloredTextWrapperTitleBackingSurvivesSpriteBackgroundTransparentInk() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("VB"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fontstyle", Datum.of("plain"));
        member.setProp("alignment", Datum.symbol("center"));
        member.setProp("fixedlinespace", Datum.of(11));
        member.setProp("wordwrap", Datum.of(0));
        member.setProp("rect", new Datum.Rect(0, 0, 168, 11));
        member.setProp("color", new Datum.Color(238, 238, 238));
        member.setProp("bgcolor", new Datum.Color(0x67, 0x94, 0xA7));
        member.setProp("text", Datum.of("¿Cómo se llama tu Habbo?"));

        Bitmap textImage = ((Datum.ImageRef) member.getProp("image")).bitmap();
        Bitmap wrapperImage = new Bitmap(168, 15, 32);
        wrapperImage.fill(0xFF6794A7);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(wrapperImage), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(textImage),
                        new Datum.Rect(0, 2, 168, 13),
                        new Datum.Rect(0, 0, 168, 11),
                        propList("ink", Datum.of(8))));

        assertFalse(wrapperImage.isTextRenderedImage(),
                "a filled colored Text Wrapper canvas must not inherit source text background-key metadata");

        CastMember dynamicTitle = new CastMember(1, 1, MemberType.BITMAP);
        dynamicTitle.setBitmapDirectly(wrapperImage);
        Bitmap processed = new BitmapCache().getProcessedScriptModifiedDynamic(
                dynamicTitle,
                InkMode.BACKGROUND_TRANSPARENT.code(),
                0xFFFFFF,
                0,
                false,
                false,
                false);

        assertTrue(countPixels(processed, 0xFF6794A7) > 0,
                "ink 36 must keep the authored teal title backing instead of keying it as text transparency");
        assertTrue(countPixels(processed, 0xFFEEEEEE) > 0,
                "the localized title glyphs should remain visible over the teal backing");
    }

    @Test
    void explicitBottomBarTextRenderKeepsWhiteTextOnBlackBacking() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(11));
        member.setProp("rect", new Datum.Rect(0, 0, 90, 10));
        member.setProp("color", new Datum.Color(255, 255, 255));
        member.setProp("bgcolor", new Datum.Color(0, 0, 0));
        member.setProp("text", Datum.of("Premium Club"));

        Bitmap textImage = member.renderTextToImage();

        assertTrue(countWhitePixels(textImage) > 0,
                "explicit bottom bar render should leave readable white glyph pixels");
        assertEquals(0xFF000000, textImage.getPixel(0, 0),
                "explicit bottom bar render should preserve the black backing");
    }

    @Test
    void writerStyleMaskedTintedTextPipelineProducesVisibleTealLinkPixels() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("Courier"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fontstyle", Datum.of("underline"));
        member.setProp("alignment", Datum.symbol("right"));
        member.setProp("fixedlinespace", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 165, 14));
        member.setProp("color", new Datum.Color(0, 0, 0));
        member.setProp("bgcolor", new Datum.Color(255, 255, 255));
        member.setProp("text", Datum.of("Hide Full Rooms"));

        Bitmap textMaskSource = ((Datum.ImageRef) member.getProp("image")).bitmap();
        Bitmap mask = new Bitmap(textMaskSource.getWidth(), textMaskSource.getHeight(), 8);
        mask.fill(0xFFFFFFFF);
        Drawing.copyPixels(mask, textMaskSource, 0, 0, 0, 0,
                textMaskSource.getWidth(), textMaskSource.getHeight(),
                Palette.InkMode.MATTE, 255);

        Bitmap tinted = new Bitmap(textMaskSource.getWidth(), textMaskSource.getHeight(), 32);
        tinted.fill(0xFF7B9498);
        Bitmap out = new Bitmap(textMaskSource.getWidth(), textMaskSource.getHeight(), 32);
        out.fill(0xFFFFFFFF);

        Datum.PropList props = new Datum.PropList();
        props.add("maskImage", new Datum.ImageRef(mask), true);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(out), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(tinted),
                        new Datum.Rect(0, 0, out.getWidth(), out.getHeight()),
                        new Datum.Rect(0, 0, tinted.getWidth(), tinted.getHeight()),
                        props));

        assertTrue(countPixels(out, 0xFF7B9498) >= 20,
                "writer-style tinted mask copy should leave visible teal link pixels");
    }

    @Test
    void writerStyleMaskedBodyTextKeepsMostLetterInk() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fixedlinespace", Datum.of(11));
        member.setProp("rect", new Datum.Rect(0, 0, 190, 44));
        member.setProp("color", new Datum.Color(0, 0, 0));
        member.setProp("bgcolor", new Datum.Color(255, 255, 255));
        member.setProp("wordwrap", Datum.of(1));
        member.setProp("text", Datum.of("Please reload the client. If this happens again, please wait a moment before reloading."));

        Bitmap textMaskSource = ((Datum.ImageRef) member.getProp("image")).bitmap();
        Bitmap mask = new Bitmap(textMaskSource.getWidth(), textMaskSource.getHeight(), 8);
        mask.fill(0xFFFFFFFF);
        Drawing.copyPixels(mask, textMaskSource, 0, 0, 0, 0,
                textMaskSource.getWidth(), textMaskSource.getHeight(),
                Palette.InkMode.MATTE, 255);

        Bitmap tinted = new Bitmap(textMaskSource.getWidth(), textMaskSource.getHeight(), 32);
        tinted.fill(0xFF000000);
        Bitmap out = new Bitmap(textMaskSource.getWidth(), textMaskSource.getHeight(), 32);
        out.fill(0xFFFFFFFF);

        Datum.PropList props = new Datum.PropList();
        props.add("maskImage", new Datum.ImageRef(mask), true);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(out), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(tinted),
                        new Datum.Rect(0, 0, out.getWidth(), out.getHeight()),
                        new Datum.Rect(0, 0, tinted.getWidth(), tinted.getHeight()),
                        props));

        assertTrue(countPixels(out, 0xFF000000) >= 250,
                "writer-style body text mask should keep full letter shapes, not just punctuation fragments");
    }

    @Test
    void fillWithSymbolicColorPropListAppliesRequestedTeal() {
        Bitmap target = new Bitmap(4, 3, 32);

        Datum.PropList props = new Datum.PropList();
        props.add("color", new Datum.Color(0x7B, 0x94, 0x98), true);
        props.add("shape", Datum.symbol("rect"), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(target), "fill",
                java.util.List.of(new Datum.Rect(0, 0, 4, 3), props));

        assertEquals(12, countPixels(target, 0xFF7B9498),
                "fill(rect, [#color: teal]) should tint the entire target image");
    }

    @Test
    void navigatorHideLinkPipelineSurvivesMaskingAndNegativeOffset() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("Courier"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fontstyle", Datum.of("underline"));
        member.setProp("alignment", Datum.symbol("right"));
        member.setProp("fixedlinespace", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 165, 14));
        member.setProp("color", new Datum.Color(0, 0, 0));
        member.setProp("bgcolor", new Datum.Color(255, 255, 255));
        member.setProp("text", Datum.of("Hide Full Rooms"));

        Bitmap textMaskSource = ((Datum.ImageRef) member.getProp("image")).bitmap();
        Bitmap mask = new Bitmap(textMaskSource.getWidth(), textMaskSource.getHeight(), 8);
        mask.fill(0xFFFFFFFF);
        Drawing.copyPixels(mask, textMaskSource, 0, 0, 0, 0,
                textMaskSource.getWidth(), textMaskSource.getHeight(),
                Palette.InkMode.MATTE, 255);

        Bitmap tinted = new Bitmap(textMaskSource.getWidth(), textMaskSource.getHeight(), 32);
        Datum.PropList fillProps = new Datum.PropList();
        fillProps.add("color", new Datum.Color(0x7B, 0x94, 0x98), true);
        fillProps.add("shape", Datum.symbol("rect"), true);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(tinted), "fill",
                java.util.List.of(new Datum.Rect(0, 0, tinted.getWidth(), tinted.getHeight()), fillProps));

        Bitmap wrapperImage = new Bitmap(165, 14, 32);
        wrapperImage.fill(0xFFFFFFFF);
        Datum.PropList maskProps = new Datum.PropList();
        maskProps.add("maskImage", new Datum.ImageRef(mask), true);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(wrapperImage), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(tinted),
                        new Datum.Rect(0, 0, wrapperImage.getWidth(), wrapperImage.getHeight()),
                        new Datum.Rect(0, 0, wrapperImage.getWidth(), wrapperImage.getHeight()),
                        maskProps));

        assertTrue(countPixels(wrapperImage, 0xFF7B9498) > 0,
                "maskImage copy should create teal link pixels inside the wider wrapper image");

        Bitmap stageBuffer = new Bitmap(165, 14, 32);
        stageBuffer.fill(0xFFD4DDE1);
        Datum.PropList wrapperProps = new Datum.PropList();
        wrapperProps.add("ink", Datum.of(36), true);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(stageBuffer), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(wrapperImage),
                        new Datum.Rect(0, 0, stageBuffer.getWidth(), stageBuffer.getHeight()),
                        new Datum.Rect(-67, 0, 98, 14),
                        wrapperProps));

        assertTrue(countPixels(stageBuffer, 0xFF7B9498) > 0,
                "negative source offsets with ink 36 should keep the teal link visible");
        assertTrue(findLastColorColumn(stageBuffer, 0xFF7B9498) >= stageBuffer.getWidth() - 6,
                "the hide link should remain right-aligned after the wrapper offset is applied");
    }

    @Test
    void adjustTextImageCanGrowWiderThanRectForUnwrappedCourierLinkText() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("Courier"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fontstyle", Datum.of("underline"));
        member.setProp("alignment", Datum.symbol("right"));
        member.setProp("fixedlinespace", Datum.of(9));
        member.setProp("rect", new Datum.Rect(0, 0, 60, 14));
        member.setProp("color", new Datum.Color(0x7B, 0x94, 0x98));
        member.setProp("bgcolor", new Datum.Color(0xFF, 0xFF, 0xFF));
        member.setProp("text", Datum.of("Hide Full Rooms"));

        Bitmap textImage = member.renderTextToImage();

        assertTrue(textImage.getWidth() > 60,
                "adjust-to-fit link text images should grow wide enough for the actual glyph metrics");
    }

    @Test
    void runtimeWriterMemberImageShrinksLargeAdjustCanvasForInfostandNames() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("VB"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fontstyle", Datum.of("plain"));
        member.setProp("lineheight", Datum.of(10));
        member.setProp("rect", new Datum.Rect(0, 0, 480, 480));
        member.setProp("color", new Datum.Color(0xEE, 0xEE, 0xEE));
        member.setProp("text", Datum.of("Yellow Two-Seater Sofa"));

        Bitmap titleImage = ((Datum.ImageRef) member.getProp("image")).bitmap();
        assertTrue(titleImage.getWidth() < 221,
                "Writer Class scratch members should expose the fitted title image, not the 480px measure canvas");
        assertTrue(countPixels(titleImage, 0xFFEEEEEE) > 0,
                "the fitted title image should contain visible glyph pixels");

        Bitmap infostandNameSlot = new Bitmap(221, 16, 32);
        infostandNameSlot.fill(0xFF5F5F5F);
        int sourceX = (titleImage.getWidth() - infostandNameSlot.getWidth()) / 2;
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(infostandNameSlot), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(titleImage),
                        new Datum.Rect(0, 0, infostandNameSlot.getWidth(), infostandNameSlot.getHeight()),
                        new Datum.Rect(sourceX, 0, sourceX + infostandNameSlot.getWidth(), infostandNameSlot.getHeight()),
                        propList("ink", Datum.of(36))));

        assertTrue(countPixels(infostandNameSlot, 0xFFEEEEEE) > 0,
                "the centered infostand title slot should receive the rendered furni name");
    }

    @Test
    void infostandFurnitureNameSlotCentersShortWriterImage() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember writerMember = new CastMember(1, 1, MemberType.TEXT);
        writerMember.setProp("font", Datum.of("VB"));
        writerMember.setProp("fontsize", Datum.of(9));
        writerMember.setProp("fontstyle", Datum.of("plain"));
        writerMember.setProp("lineheight", Datum.of(10));
        writerMember.setProp("rect", new Datum.Rect(0, 0, 480, 480));
        writerMember.setProp("color", new Datum.Color(0xEE, 0xEE, 0xEE));
        writerMember.setProp("text", Datum.of("Two-Seater Sofa"));

        Bitmap titleImage = ((Datum.ImageRef) writerMember.getProp("image")).bitmap().copy();
        assertTrue(titleImage.getWidth() < 117,
                "this real Origins furni title should take the scroller's negative centering path");
        assertTrue(countPixels(titleImage, 0xFFEEEEEE) > 0,
                "the writer scratch image should contain the rendered furni name before feedImage");

        Bitmap roomObjDispNameBuffer = new Bitmap(144, 19, 32);
        roomObjDispNameBuffer.fill(0xFF000000);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(roomObjDispNameBuffer), "fill",
                java.util.List.of(
                        new Datum.Rect(27, 7, 144, 19),
                        new Datum.Color(0x5F, 0x5F, 0x5F)));

        int sourceX = (titleImage.getWidth() - 117) / 2;
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(roomObjDispNameBuffer), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(titleImage),
                        new Datum.Rect(27, 7, 144, 19),
                        new Datum.Rect(sourceX, 0, sourceX + 117, 12),
                        propList("ink", Datum.of(36))));

        assertTrue(countPixels(roomObjDispNameBuffer, 0xFFEEEEEE) > 0,
                "the real 117x12 room_obj_disp_name slot should receive the centered furni title");
    }

    @Test
    void infostandFurnitureNameSlotStaysBlankWhenWriterReceivesEmptyName() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember writerMember = new CastMember(1, 1, MemberType.TEXT);
        writerMember.setProp("font", Datum.of("VB"));
        writerMember.setProp("fontsize", Datum.of(9));
        writerMember.setProp("fontstyle", Datum.of("plain"));
        writerMember.setProp("lineheight", Datum.of(10));
        writerMember.setProp("rect", new Datum.Rect(0, 0, 480, 480));
        writerMember.setProp("color", new Datum.Color(0xEE, 0xEE, 0xEE));
        writerMember.setProp("text", Datum.EMPTY_STRING);

        Bitmap titleImage = ((Datum.ImageRef) writerMember.getProp("image")).bitmap().copy();
        assertEquals(0, countPixels(titleImage, 0xFFEEEEEE),
                "an empty info struct name should render no furni title glyphs");

        Bitmap roomObjDispNameBuffer = new Bitmap(144, 19, 32);
        roomObjDispNameBuffer.fill(0xFF000000);
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(roomObjDispNameBuffer), "fill",
                java.util.List.of(
                        new Datum.Rect(27, 7, 144, 19),
                        new Datum.Color(0x5F, 0x5F, 0x5F)));

        int sourceX = (titleImage.getWidth() - 117) / 2;
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(roomObjDispNameBuffer), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(titleImage),
                        new Datum.Rect(27, 7, 144, 19),
                        new Datum.Rect(sourceX, 0, sourceX + 117, 12),
                        propList("ink", Datum.of(36))));

        assertEquals(0, countPixels(roomObjDispNameBuffer, 0xFFEEEEEE),
                "if renderObjectDisplayName receives an empty name, the infobar slot will stay visually blank");
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

    private static String repeatedScrollableText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 18; i++) {
            if (i > 0) {
                text.append('\r');
            }
            text.append("Read this agreement carefully before entering the service. ")
                    .append("The text wrapper must keep every wrapped line available to the scrollbar.");
        }
        return text.toString();
    }

    private static String sampleScrollableAgreementText() {
        return "Sample Service - Terms of Service\r\r"
                + "Version: 13.6.2024\r"
                + "1. General\r\r"
                + "1.1. The terms of this agreement and the applicable service policies, including community rules "
                + "(\"Terms of Service\" or \"Terms\") govern the relationship between you and Sulake Oy (hereinafter "
                + "\"Sulake\", \"us\", \"our\" or \"we\") regarding your use of the service located at example.invalid or "
                + "our other sites or services that apply these Terms (the \"Service(s)\"), whether accessed by computer, "
                + "mobile phone or other device (each, a \"Device\"). The applicable service policies are hereafter referred to "
                + "as \"Applicable Policies\".\r\r"
                + "1.2. BY INSTALLING, USING OR OTHERWISE ACCESSING THE SERVICE, YOU AGREE TO THESE TERMS OF SERVICE. "
                + "IF YOU DO NOT AGREE, YOU DO NOT HAVE PERMISSION TO INSTALL, USE OR OTHERWISE ACCESS THE SERVICE. "
                + "ANY EXPRESS OR IMPLIED PERMISSION TO USE THE SERVICE IS VOID WHERE PROHIBITED.";
    }

    private static Datum.PropList propList(String key, Datum value) {
        Datum.PropList props = new Datum.PropList();
        props.add(key, value, true);
        return props;
    }

    private static void fillWhite(Bitmap bitmap) {
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(bitmap), "fill",
                java.util.List.of(new Datum.Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()),
                        new Datum.Color(255, 255, 255)));
    }

    private static void copyTermsSlice(Bitmap target, Bitmap source, int offsetY, Datum.PropList props) {
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(target), "copyPixels",
                java.util.List.of(
                        new Datum.ImageRef(source),
                        new Datum.Rect(0, 0, target.getWidth(), target.getHeight()),
                        new Datum.Rect(0, offsetY, target.getWidth(), offsetY + target.getHeight()),
                        props));
    }

    private static CastMember compactTermsLink(String label, String fontStyle, int height) {
        CastMember member = new CastMember(1, 1, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(9));
        member.setProp("fontstyle", Datum.of(fontStyle));
        member.setProp("lineheight", Datum.of(10));
        member.setProp("wordwrap", Datum.of(1));
        member.setProp("alignment", Datum.of("left"));
        member.setProp("rect", new Datum.Rect(0, 0, 229, height));
        member.setProp("boxtype", Datum.symbol("fixed"));
        member.setProp("txtColor", Datum.of("#990000"));
        member.setProp("txtBgColor", Datum.of("#FFFFFF"));
        member.setProp("text", Datum.of(label));
        return member;
    }

    private static Bitmap underlinedTextImage(CastMember member) {
        return member.renderTextToImage();
    }

    private static void assertLeftCropEquals(Bitmap source, Bitmap clipped, String boxType) {
        for (int y = 0; y < clipped.getHeight(); y++) {
            for (int x = 0; x < clipped.getWidth(); x++) {
                assertEquals(source.getPixel(x, y), clipped.getPixel(x, y),
                        "Director #" + boxType + " should clip overflow, not rescale glyphs at "
                                + x + "," + y);
            }
        }
    }

    private static int findAddedColorRow(Bitmap plain, Bitmap underlined, int argb) {
        for (int y = underlined.getHeight() - 1; y >= 0; y--) {
            int plainPixels = y < plain.getHeight() ? countColorPixelsOnRow(plain, y, argb) : 0;
            int underlinedPixels = countColorPixelsOnRow(underlined, y, argb);
            if (underlinedPixels > plainPixels + 8) {
                return y;
            }
        }
        return -1;
    }

    private static int countWhitePixels(Bitmap bitmap) {
        return countPixels(bitmap, 0xFFFFFFFF);
    }

    private static int countPixels(Bitmap bitmap, int argb) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int pixel = bitmap.getPixel(x, y);
                if (((pixel >>> 24) & 0xFF) != 0 && pixel == argb) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countTransparentPixels(Bitmap bitmap) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (((bitmap.getPixel(x, y) >>> 24) & 0xFF) == 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int findFirstColorColumn(Bitmap bitmap, int argb) {
        for (int x = 0; x < bitmap.getWidth(); x++) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                if (bitmap.getPixel(x, y) == argb) {
                    return x;
                }
            }
        }
        return -1;
    }

    private static int findLastColorColumn(Bitmap bitmap, int argb) {
        for (int x = bitmap.getWidth() - 1; x >= 0; x--) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                if (bitmap.getPixel(x, y) == argb) {
                    return x;
                }
            }
        }
        return -1;
    }

    private static int findLastColorRow(Bitmap bitmap, int argb) {
        for (int y = bitmap.getHeight() - 1; y >= 0; y--) {
            if (countColorPixelsOnRow(bitmap, y, argb) > 0) {
                return y;
            }
        }
        return -1;
    }

    private static int countColorPixelsOnRow(Bitmap bitmap, int y, int argb) {
        int count = 0;
        for (int x = 0; x < bitmap.getWidth(); x++) {
            if (bitmap.getPixel(x, y) == argb) {
                count++;
            }
        }
        return count;
    }
}
