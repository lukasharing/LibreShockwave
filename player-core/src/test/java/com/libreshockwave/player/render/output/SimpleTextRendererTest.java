package com.libreshockwave.player.render.output;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.chunks.RawChunk;
import com.libreshockwave.font.BitmapFont;
import com.libreshockwave.font.TtfBitmapRasterizer;
import com.libreshockwave.player.cast.FontRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleTextRendererTest {

    @Test
    void underlineDoesNotForceAutosizedBitmapFontTextTaller() {
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap plain = renderer.renderText("Open", 33, 0,
                "Verdana", 9, "plain",
                "left", 0xFF000000, 0x00FFFFFF,
                false, false, 9, 1);
        Bitmap underlined = renderer.renderText("Open", 33, 0,
                "Verdana", 9, "underline",
                "left", 0xFF000000, 0x00FFFFFF,
                false, false, 9, 1);

        int underlineRow = findLastOpaqueRow(underlined);
        int plainPixelsOnUnderlineRow = countOpaquePixelsOnRow(plain, underlineRow);
        int underlinedPixelsOnUnderlineRow = countOpaquePixelsOnRow(underlined, underlineRow);

        assertEquals(plain.getHeight(), underlined.getHeight(),
                "underline should be drawn from font metrics inside the normal line box, not add an extra row");
        assertTrue(underlinedPixelsOnUnderlineRow > plainPixelsOnUnderlineRow,
                "expected underline to add pixels below the glyph row, plain=" + plainPixelsOnUnderlineRow
                        + " underlined=" + underlinedPixelsOnUnderlineRow);
        assertTrue(underlinedPixelsOnUnderlineRow >= 20,
                "expected visible underline coverage below glyph ink, got " + underlinedPixelsOnUnderlineRow);
    }

    @Test
    void underlineDoesNotOverwriteBitmapFontGlyphInk() {
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap plain = renderer.renderText("You can create one here.", 160, 0,
                "V", 9, "plain",
                "left", 0xFF000000, 0x00FFFFFF,
                false, false, 9, 1);
        Bitmap underlined = renderer.renderText("Open", 33, 0,
                "Verdana", 9, "underline",
                "left", 0xFF000000, 0x00FFFFFF,
                false, false, 9, 1);
        Bitmap underlinedLink = renderer.renderText("You can create one here.", 160, 0,
                "V", 9, "underline",
                "left", 0xFF000000, 0x00FFFFFF,
                false, false, 9, 1);

        int plainGlyphBottom = findLastOpaqueRow(plain);
        int underlineRow = findLastOpaqueRow(underlinedLink);

        assertTrue(underlineRow >= plainGlyphBottom + 1,
                "expected underline to sit below the plain glyph ink");
        assertEquals(countOpaquePixelsOnRow(plain, plainGlyphBottom),
                countOpaquePixelsOnRow(underlinedLink, plainGlyphBottom),
                "underline should not add pixels to the glyph's bottom row");

        int compactUnderlineRow = findLastOpaqueRow(underlined);
        int compactGlyphBottom = findOpaqueRowBefore(underlined, compactUnderlineRow);
        assertEquals(compactUnderlineRow - 1, compactGlyphBottom,
                "expected compact underline to sit directly below the prior glyph ink");
    }

    @Test
    void directorLinkUnderlineFitsFixedVolterLineBox() {
        FontRegistry.clear();
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        int expectedUnderlineRow = -1;
        for (String link : new String[]{"Hotel rules", "Terms and Conditions", "Privacy Pledge"}) {
            Bitmap plain = renderer.renderText(link, 180, 11,
                    "v", 9, "plain",
                    "left", 0xFFCC0000, 0xFFFFFFFF,
                    false, false, 10, 0);
            Bitmap underlined = renderer.renderText(link, 180, 11,
                    "v", 9, "underline",
                    "left", 0xFFCC0000, 0xFFFFFFFF,
                    false, false, 10, 0);

            int underlineRow = findAddedColorRow(plain, underlined, 0xFFCC0000);
            int glyphBottom = findLastColorRow(plain, 0xFFCC0000);

            assertEquals(11, underlined.getHeight(),
                    "fixed-height Director link fields must not grow beyond their element rect");
            if (expectedUnderlineRow < 0) {
                expectedUnderlineRow = underlineRow;
            }
            assertEquals(expectedUnderlineRow, underlineRow,
                    "Director underline should follow the font baseline, not the current glyph ink, for: " + link);
            assertTrue(underlineRow >= glyphBottom,
                    "expected link underline to stay at or below the glyph ink, for: "
                            + link + " glyphBottom=" + glyphBottom + " underlineRow=" + underlineRow);
            assertTrue(countPixelsOnRow(underlined, underlineRow, 0xFFCC0000) >= 40,
                    "expected visible underline coverage for: " + link);
        }
    }

    @Test
    void underlineClipsOutOfSingleLineFixedFieldWhenNoMetricRowExists() {
        FontRegistry.clear();
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap plain = renderer.renderText("Terms and Conditions", 180, 9,
                "v", 9, "plain",
                "left", 0xFFCC0000, 0xFFFFFFFF,
                false, false, 9, 0);
        Bitmap underlined = renderer.renderText("Terms and Conditions", 180, 9,
                "v", 9, "underline",
                "left", 0xFFCC0000, 0xFFFFFFFF,
                false, false, 9, 0);

        int underlineRow = findAddedColorRow(plain, underlined, 0xFFCC0000);

        assertEquals(9, underlined.getHeight(),
                "fixed-height text fields must keep their authored rect");
        assertEquals(-1, underlineRow,
                "underline should be clipped when baseline metrics place it outside the visible rect");
    }

    @Test
    void preservesBlankLinesWhenAutosizingText() {
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap singleBreak = renderer.renderText("A\r\nB", 20, 0,
                "Verdana", 9, "plain",
                "left", 0xFF000000, 0x00FFFFFF,
                false, false, 9, 0);
        Bitmap blankLine = renderer.renderText("A\r\n\r\nB", 20, 0,
                "Verdana", 9, "plain",
                "left", 0xFF000000, 0x00FFFFFF,
                false, false, 9, 0);

        assertEquals(singleBreak.getHeight() + 9, blankLine.getHeight(),
                "expected preserved empty line to add one full line advance");
    }

    @Test
    void onePixelDirectorTextFieldsAutoExpandInsteadOfClipping() {
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap text = renderer.renderText("Error occured, press 'OK' to restart program.\r\r"
                        + "Please reload Habbo Hotel!\r\r"
                        + "If this happens again, please wait a moment before reloading.",
                318, 1,
                "Verdana", 10, "plain",
                "left", 0xFF000000, 0x00FFFFFF,
                true, false, 11, 1);

        assertTrue(text.getHeight() > 40,
                "expected a one-pixel Director text rect to auto-size to its line content");
        assertTrue(countPixels(text, 0xFF000000) > 400,
                "expected visible body text instead of a one-pixel dotted clipping artifact");
    }

    @Test
    void onePixelDefaultLeadingDoesNotExpandBitmapFontLineAdvance() {
        FontRegistry.clear();
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap text = renderer.renderText("Public Spaces\n(0/0)",
                160, 0,
                "V", 9, "plain",
                "left", 0xFF000000, 0x00FFFFFF,
                false, false, 9, 1);

        assertEquals(19, text.getHeight(),
                "expected one-pixel default leading to avoid adding interline spacing while preserving the bitmap glyph cell");
    }

    @Test
    void twoLineDefaultLeadingFitsExactHeightBitmapField() {
        FontRegistry.clear();
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap text = renderer.renderText("Haven't got a Habbo yet?\nYou can create one here.",
                180, 18,
                "VB", 9, "plain",
                "left", 0xFF000000, 0xFFFFFFFF,
                false, false, 9, 1);

        assertTrue(countOpaquePixelsOnRow(text, 17) > 0,
                "expected the second line's bottom pixels to fit inside an exact two-line field");
    }

    @Test
    void underlineUsesAvailableRowBelowSingleLineWindowText() {
        FontRegistry.clear();
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap plain = renderer.renderText("You can create one here.",
                176, 27,
                "vb", 9, "plain",
                "center", 0xFF000000, 0xFFFFFFFF,
                true, false, 9, 0);
        Bitmap underlined = renderer.renderText("You can create one here.",
                176, 27,
                "vb", 9, "underline",
                "center", 0xFF000000, 0xFFFFFFFF,
                true, false, 9, 0);

        int glyphBottom = findLastColorRow(plain, 0xFF000000);
        int underlineRow = findAddedColorRow(plain, underlined, 0xFF000000);

        assertTrue(underlineRow >= glyphBottom + 1,
                "expected link underline to use the spare row below the glyph ink");
        assertTrue(underlineRow <= glyphBottom + 3,
                "tall Director link fields should keep the underline close to the baseline without touching glyph ink");
    }

    @Test
    void tightTwoLineUnderlineDoesNotCutNextLineGlyphs() {
        FontRegistry.clear();
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap plain = renderer.renderText("Haven't got a Habbo yet?\nYou can create one here.",
                180, 18,
                "VB", 9, "plain",
                "center", 0xFF000000, 0xFFFFFFFF,
                false, false, 9, 1);
        Bitmap underlined = renderer.renderText("Haven't got a Habbo yet?\nYou can create one here.",
                180, 19,
                "VB", 9, "underline",
                "center", 0xFF000000, 0xFFFFFFFF,
                false, false, 9, 1);

        for (int y = 8; y <= 11; y++) {
            assertEquals(countOpaquePixelsOnRow(plain, y), countOpaquePixelsOnRow(underlined, y),
                    "underline from the first tight line must not be drawn over the next line at y=" + y);
        }
        int finalUnderlineRow = findLastOpaqueRow(underlined);
        assertTrue(finalUnderlineRow > findLastOpaqueRow(plain)
                        && countOpaquePixelsOnRow(underlined, finalUnderlineRow) >= 40,
                "expected the final line to keep its underline when there is room below it");
    }

    @Test
    void explicitBitmapFontLineSpacingStillExpandsLineAdvance() {
        FontRegistry.clear();
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap text = renderer.renderText("Public Spaces\n(0/0)",
                160, 0,
                "V", 9, "plain",
                "left", 0xFF000000, 0x00FFFFFF,
                false, false, 9, 9);

        assertEquals(37, text.getHeight(),
                "expected explicit extra leading to keep the larger Director line advance and preserve the bitmap glyph cell");
    }

    @Test
    void locToCharPosTreatsLfAsLineBreak() {
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        int charPos = renderer.locToCharPos("A\nB", 0, 9,
                "Verdana", 9, "plain",
                9, "left", 20);

        assertEquals(2, charPos,
                "expected click on second line to map after the LF break");
    }

    @Test
    void charPosToLocUsesDirectorOneBasedCharacterPositions() {
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        int[] first = renderer.charPosToLoc("Go", 1,
                "Courier", 9, "plain",
                10, "left", 0);
        int[] second = renderer.charPosToLoc("Go", 2,
                "Courier", 9, "plain",
                10, "left", 0);
        int[] afterEnd = renderer.charPosToLoc("Go", 3,
                "Courier", 9, "plain",
                10, "left", 0);

        assertEquals(0, first[0]);
        assertEquals(6, second[0]);
        assertEquals(11, afterEnd[0]);
    }

    @Test
    void caretBoundsUseFontSizeInsideInputLineBox() {
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        int[] bounds = renderer.getCaretBounds("V", 9, "plain", 18);

        assertTrue(bounds[0] > 0,
                "expected caret to be vertically inset inside an oversized field line");
        assertEquals(9, bounds[1],
                "editable caret height should follow the member font size");
    }

    @Test
    void charPosToLocDoesNotReturnNegativeAlignedOverflow() {
        FontRegistry.clear();
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        int[] afterTitle = renderer.charPosToLoc("Hotel Navigator", 16,
                "VB", 9, "plain",
                10, "center", 50);

        assertTrue(afterTitle[0] >= 90,
                "expected overflowing centered text to measure full content width");
    }

    @Test
    void editableCenteredOverflowKeepsEndBoundaryAtVisibleRightEdge() {
        FontRegistry.clear();
        SimpleTextRenderer renderer = new SimpleTextRenderer();
        int fieldWidth = 30;
        String text = "ABCDEFGHIJKLMNOP";

        int[] afterEnd = renderer.charPosToLoc(text, text.length() + 1,
                "Courier", 9, "plain",
                10, "editable-center", fieldWidth);

        assertTrue(afterEnd[0] >= fieldWidth,
                "expected editable overflow to keep the caret at the visible right edge");
        assertTrue(afterEnd[0] <= fieldWidth + 2,
                "expected editable overflow to avoid placing the caret outside the input");
    }

    @Test
    void findCharLineTreatsCrLfAsSingleLineBreak() {
        assertArrayEquals(new int[]{0, 1}, TextRenderer.findCharLine("A\r\nB", 2));
        assertArrayEquals(new int[]{1, 0}, TextRenderer.findCharLine("A\r\nB", 4));
        assertEquals(3, TextRenderer.lineStartIndex("A\r\nB", 1));
    }

    @Test
    void wordWrapCanBreakHyphenatedWordsAtFittingHyphen() {
        java.util.List<String> lines = new java.util.ArrayList<>();

        TextRenderer.wrapLine("Relax! It's faux-fur",
                s -> s.length(),
                "Relax! It's faux-".length(),
                lines);

        assertArrayEquals(new String[]{"Relax! It's faux-", "fur"}, lines.toArray(String[]::new));
    }

    @Test
    void leftAlignedBitmapFontTextKeepsInkOffTheImageEdge() {
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap text = renderer.renderText("How To Get?", 80, 0,
                "Verdana", 9, "plain",
                "left", 0xFFFFFFFF, 0xFF000000,
                false, false, 9, 0);

        assertTrue(findFirstNonBackgroundColumn(text, 0xFF000000) > 0,
                "expected the first glyph to preserve its font bearing instead of touching the image edge");
    }

    @Test
    void leftAlignedVolterLinkTextKeepsInkOffTheImageEdge() {
        FontRegistry.clear();
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap updateId = renderer.renderText("Update My Habbo ID >>", 147, 10,
                "V", 9, "underline",
                "left", 0xFFFFFFFF, 0xFF000000,
                false, false, 0, 0);
        Bitmap howToGet = renderer.renderText("How To get?", 80, 10,
                "V", 9, "underline",
                "left", 0xFFFFFFFF, 0xFF000000,
                false, false, 0, 0);

        assertTrue(findFirstNonBackgroundColumn(updateId, 0xFF000000) > 0,
                "tight Volter link fields need a one-pixel guard before the first glyph");
        assertTrue(findFirstNonBackgroundColumn(howToGet, 0xFF000000) > 0,
                "short Volter link labels should not start in the image edge column");
    }

    @Test
    void directorFontAliasUsesEmbeddedVolterBoldMetrics() {
        FontRegistry.clear();
        try {
            FontRegistry.registerFontAlias("vb", "Volter", true);
            SimpleTextRenderer renderer = new SimpleTextRenderer();

            int[] afterTitle = renderer.charPosToLoc("Hotel Navigator", 16,
                    "vb", 9, "plain",
                    10, "left", 200);

            assertTrue(afterTitle[0] >= 90,
                    "expected Director alias vb to resolve to the wider Volter bold metrics");
        } finally {
            FontRegistry.clear();
        }
    }

    @Test
    void directorVolterShortAliasFallsBackToBundledBoldMetrics() {
        FontRegistry.clear();
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap title = renderer.renderText("Hotel Navigator", 200, 15,
                "VB", 9, "plain",
                "left", 0xFFEEEEEE, 0xFF6794A7,
                false, false, 10, 0);

        assertEquals(284, countPixels(title, 0xFFEEEEEE),
                "expected unregistered Director font alias VB to use bundled Volter bold");
    }

    @Test
    void compactButtonLineSpaceDoesNotClipVolterGlyphTops() {
        FontRegistry.clear();
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap compact = renderer.renderText("OK", 32, 11,
                "VB", 9, "plain",
                "center", 0xFF000000, 0xFFFFFFFF,
                false, false, 11, 0);
        Bitmap relaxed = renderer.renderText("OK", 32, 18,
                "VB", 9, "plain",
                "center", 0xFF000000, 0xFFFFFFFF,
                false, false, 18, 0);

        int compactTop = findFirstColorRow(compact, 0xFF000000);
        int compactBlack = countPixels(compact, 0xFF000000);
        int relaxedBlack = countPixels(relaxed, 0xFF000000);

        assertTrue(compactTop >= 0,
                "expected compact button text to render visible black glyph pixels");
        assertTrue(countPixelsOnRow(compact, compactTop, 0xFF000000) >= 4,
                "expected the first visible row of compact button glyphs to keep the rounded O top");
        assertTrue(compactBlack >= relaxedBlack - 4,
                "compact fixedLineSpace should not drop the upper glyph rows from OK");
    }

    @Test
    void embeddedPfrVolterUsesRequestedSizeWithoutAutomaticCondensing() throws Exception {
        registerInterfaceVolterBold();
        FontRegistry.registerFontAlias("vb", "Volter", true);

        BitmapFont resolved = resolveBitmapFont("vb", 9, false, false);
        BitmapFont natural = TtfBitmapRasterizer.rasterize(FontRegistry.getTtfBytes("vb"), 9, "vb");

        assertEquals("vb", resolved.getFontName(),
                "the cast-embedded PFR font member must win over the generic Volter alias fallback");
        assertNotNull(natural);
        int roomTitleWidth = resolved.getStringWidth("Archive #5 - Grand Palace - MY PALACE 20");
        int naturalTitleWidth = natural.getStringWidth("Archive #5 - Grand Palace - MY PALACE 20");

        assertEquals(naturalTitleWidth, roomTitleWidth,
                "PFR outline text should use the requested type size, not an inferred compact screen strike");
        assertTrue(roomTitleWidth > 184,
                () -> "overflowing PFR text should be clipped by fixed/limit fields, not condensed to fit, width="
                        + roomTitleWidth);
        assertEquals(9, resolved.getFontSize(),
                "layout decisions should keep the nominal Director font size");
    }

    @Test
    void rawDirectorBytesRenderAsWindows1252GlyphsInEmbeddedPfrFont() throws Exception {
        registerInterfaceVolterBold();
        BitmapFont font = FontRegistry.getBitmapFont("vb", 9);
        assertNotNull(font);

        String raw = rawDirectorHabmojiCodes();
        String decoded = windows1252DecodedHabmojiCodes();
        Bitmap rawGlyphs = renderGlyphRun(font, raw);
        Bitmap decodedGlyphs = renderGlyphRun(font, decoded);

        assertArrayEquals(decodedGlyphs.getPixels(), rawGlyphs.getPixels(),
                "Director byte values 128-159 should resolve through Windows-1252 before glyph lookup");
        assertTrue(countPixels(rawGlyphs, 0xFF000000) > 120,
                "expected visible embedded PFR glyphs for the HabMoji byte sequence");
    }

    @Test
    void simpleRendererDrawsRawDirectorBytesLikeDecodedWindows1252Text() throws Exception {
        registerInterfaceVolterBold();
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap raw = renderer.renderText(rawDirectorHabmojiCodes(), 280, 18,
                "vb", 9, "plain",
                "left", 0xFF000000, 0xFFFFFFFF,
                false, false, 11, 0);
        Bitmap decoded = renderer.renderText(windows1252DecodedHabmojiCodes(), 280, 18,
                "vb", 9, "plain",
                "left", 0xFF000000, 0xFFFFFFFF,
                false, false, 11, 0);

        assertArrayEquals(decoded.getPixels(), raw.getPixels(),
                "raw Director text should render with the same glyphs as Windows-1252 decoded text");
    }

    @Test
    void embeddedPfrTextUsesDirectorSizedConvertedRasterStrike() throws Exception {
        registerInterfacePfrFont("v");
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap title = renderer.renderText("¿Cómo se llama tu Habbo?", 250, 20,
                "v", 9, "plain",
                "center", 0xFF000000, 0xFFFFFFFF,
                false, false, 11, 0);

        int firstInkRow = findFirstColorRow(title, 0xFF000000);
        int lastInkRow = findLastColorRow(title, 0xFF000000);
        int blackPixels = countPixels(title, 0xFF000000);

        assertTrue(blackPixels > 200,
                () -> "small embedded PFR text should use a readable converted TTF raster path, pixels="
                        + blackPixels + ", height=" + (lastInkRow - firstInkRow + 1));
        assertTrue(lastInkRow - firstInkRow + 1 >= 7,
                "Origins 9px text must keep the Director-sized glyph height instead of a shrunken raster strike");
    }

    @Test
    void embeddedPfrLargeTextUsesNaturalHorizontalScale() throws Exception {
        registerInterfacePfrFont("v");

        BitmapFont resolved = resolveBitmapFont("v", 18, false, false);
        BitmapFont natural = TtfBitmapRasterizer.rasterize(FontRegistry.getTtfBytes("v"), 18, "v");

        assertNotNull(natural);
        int resolvedWidth = resolved.getStringWidth("lukashargil");
        int naturalWidth = natural.getStringWidth("lukashargil");

        assertEquals(naturalWidth, resolvedWidth,
                () -> "PFR text should not inherit a compact Volter scale, resolved="
                        + resolvedWidth + " natural=" + naturalWidth);
    }

    @Test
    void originsPlainVolterRendersFurnitureInfostandTitle() throws Exception {
        registerInterfacePfrFont("v");
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap title = renderer.renderText("Two-Seater Sofa", 80, 1,
                "v", 9, "plain",
                "left", 0xFFEEEEEE, 0x00FFFFFF,
                false, false, 0, 0);

        assertTrue(title.getHeight() >= 8,
                "auto-height writer text should expose enough rows for the real Volter glyphs");
        assertTrue(countPixels(title, 0xFFEEEEEE) > 0,
                "the real Origins plain Volter font must paint visible furniture title pixels");
    }

    @Test
    void embeddedPfrMissingGlyphStaysOnPrimaryFontInsteadOfSystemFallback() throws Exception {
        registerInterfacePfrFont("vb");

        BitmapFont embedded = resolveBitmapFont("vb", 9, true, false);
        assertNotNull(embedded);
        assertFalse(embedded.allowsMissingGlyphFallback(),
                "registered PFR fonts should model subset misses as non-portable instead of system fallback");

        char missingChar = firstMissingExtendedChar(embedded);
        assertSame(embedded, fontForChar(embedded, missingChar),
                "missing glyphs inside an embedded PFR subset should not be substituted from another font");
    }

    private static BitmapFont resolveBitmapFont(String fontName, int fontSize,
                                                boolean bold, boolean italic) throws Exception {
        Method method = SimpleTextRenderer.class.getDeclaredMethod(
                "resolveBitmapFont", String.class, int.class, boolean.class, boolean.class, boolean[].class);
        method.setAccessible(true);
        return (BitmapFont) method.invoke(null, fontName, fontSize, bold, italic, new boolean[]{false});
    }

    private static BitmapFont fontForChar(BitmapFont primary, char ch) throws Exception {
        Method method = SimpleTextRenderer.class.getDeclaredMethod("fontForChar", BitmapFont.class, char.class);
        method.setAccessible(true);
        return (BitmapFont) method.invoke(null, primary, ch);
    }

    private static char firstMissingExtendedChar(BitmapFont font) {
        for (char ch = 0x0100; ch < 0x0400; ch++) {
            if (!font.canDraw(ch)) {
                return ch;
            }
        }
        throw new AssertionError("test font unexpectedly contains every extended glyph in the search range");
    }

    private static void registerInterfaceVolterBold() throws Exception {
        registerInterfacePfrFont("vb");
    }

    private static void registerInterfacePfrFont(String fontName) throws Exception {
        FontRegistry.clear();
        DirectorFile file = DirectorFile.load(fixturePath("player-wasm/build/dist/origins300/hh_interface.cct"));
        for (var member : file.getCastMembers()) {
            if (!fontName.equalsIgnoreCase(member.name())) {
                continue;
            }
            for (var entry : file.getKeyTable().getEntriesForOwner(member.id())) {
                if (!"XMED".equals(entry.fourccString())) {
                    continue;
                }
                if (file.getChunk(entry.sectionId()) instanceof RawChunk raw
                        && raw.data().length >= 4
                    && raw.data()[0] == 'P'
                    && raw.data()[1] == 'F'
                    && raw.data()[2] == 'R'
                    && raw.data()[3] == '1') {
                    FontRegistry.registerPfr1Font(fontName, raw.data());
                    return;
                }
            }
        }
        throw new AssertionError("hh_interface.cct must contain embedded PFR1 font member " + fontName);
    }

    private static Path fixturePath(String relativePath) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("fixture not found: " + relativePath);
    }

    private static String rawDirectorHabmojiCodes() {
        int[] codes = {
                124, 131, 132, 133, 134, 135, 145, 149, 151, 153,
                165, 167, 169, 170, 172, 174, 176, 177, 181, 182,
                185, 186, 187, 190, 247
        };
        StringBuilder result = new StringBuilder(codes.length);
        for (int code : codes) {
            result.append((char) code);
        }
        return result.toString();
    }

    private static String windows1252DecodedHabmojiCodes() {
        String raw = rawDirectorHabmojiCodes();
        StringBuilder result = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            result.append((char) windows1252CodePoint(raw.charAt(i)));
        }
        return result.toString();
    }

    private static int windows1252CodePoint(int code) {
        return switch (code) {
            case 0x80 -> 0x20AC;
            case 0x82 -> 0x201A;
            case 0x83 -> 0x0192;
            case 0x84 -> 0x201E;
            case 0x85 -> 0x2026;
            case 0x86 -> 0x2020;
            case 0x87 -> 0x2021;
            case 0x88 -> 0x02C6;
            case 0x89 -> 0x2030;
            case 0x8A -> 0x0160;
            case 0x8B -> 0x2039;
            case 0x8C -> 0x0152;
            case 0x8E -> 0x017D;
            case 0x91 -> 0x2018;
            case 0x92 -> 0x2019;
            case 0x93 -> 0x201C;
            case 0x94 -> 0x201D;
            case 0x95 -> 0x2022;
            case 0x96 -> 0x2013;
            case 0x97 -> 0x2014;
            case 0x98 -> 0x02DC;
            case 0x99 -> 0x2122;
            case 0x9A -> 0x0161;
            case 0x9B -> 0x203A;
            case 0x9C -> 0x0153;
            case 0x9E -> 0x017E;
            case 0x9F -> 0x0178;
            default -> code;
        };
    }

    private static Bitmap renderGlyphRun(BitmapFont font, String text) {
        int width = 280;
        int height = Math.max(12, font.getLineHeight() + 4);
        int[] pixels = new int[width * height];
        int x = 2;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            font.drawChar(ch, pixels, width, height, x, 2, 0xFF000000);
            x += font.getCharWidth(ch) + 3;
        }
        return new Bitmap(width, height, 32, pixels);
    }

    private static int countOpaquePixelsOnRow(Bitmap bitmap, int y) {
        int count = 0;
        for (int x = 0; x < bitmap.getWidth(); x++) {
            if (((bitmap.getPixel(x, y) >>> 24) & 0xFF) != 0) {
                count++;
            }
        }
        return count;
    }

    private static int findLastOpaqueRow(Bitmap bitmap) {
        for (int y = bitmap.getHeight() - 1; y >= 0; y--) {
            if (countOpaquePixelsOnRow(bitmap, y) > 0) {
                return y;
            }
        }
        return -1;
    }

    private static int findOpaqueRowBefore(Bitmap bitmap, int beforeY) {
        for (int y = beforeY - 1; y >= 0; y--) {
            if (countOpaquePixelsOnRow(bitmap, y) > 0) {
                return y;
            }
        }
        return -1;
    }

    private static int findAddedColorRow(Bitmap plain, Bitmap underlined, int color) {
        for (int y = underlined.getHeight() - 1; y >= 0; y--) {
            int plainCount = y < plain.getHeight() ? countPixelsOnRow(plain, y, color) : 0;
            int underlinedCount = countPixelsOnRow(underlined, y, color);
            if (underlinedCount > plainCount + 8) {
                return y;
            }
        }
        return -1;
    }

    private static int findFirstColorRow(Bitmap bitmap, int color) {
        for (int y = 0; y < bitmap.getHeight(); y++) {
            if (countPixelsOnRow(bitmap, y, color) > 0) {
                return y;
            }
        }
        return -1;
    }

    private static int findLastColorRow(Bitmap bitmap, int color) {
        for (int y = bitmap.getHeight() - 1; y >= 0; y--) {
            if (countPixelsOnRow(bitmap, y, color) > 0) {
                return y;
            }
        }
        return -1;
    }

    private static int countPixelsOnRow(Bitmap bitmap, int y, int color) {
        int count = 0;
        for (int x = 0; x < bitmap.getWidth(); x++) {
            if (bitmap.getPixel(x, y) == color) {
                count++;
            }
        }
        return count;
    }

    private static int findFirstNonBackgroundColumn(Bitmap bitmap, int bgColor) {
        for (int x = 0; x < bitmap.getWidth(); x++) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                if (bitmap.getPixel(x, y) != bgColor) {
                    return x;
                }
            }
        }
        return -1;
    }

    private static int countPixels(Bitmap bitmap, int color) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (bitmap.getPixel(x, y) == color) {
                    count++;
                }
            }
        }
        return count;
    }
}
