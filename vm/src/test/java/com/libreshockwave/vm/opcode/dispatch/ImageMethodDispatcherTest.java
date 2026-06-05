package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageMethodDispatcherTest {

    @Test
    void nullBackedImageReportsEmptyGeometryInsteadOfThrowing() {
        Datum.ImageRef image = new Datum.ImageRef((Bitmap) null);

        assertEquals(0, ImageMethodDispatcher.getProperty(image, "width").toInt());
        assertEquals(0, ImageMethodDispatcher.getProperty(image, "height").toInt());
        assertEquals(0, ImageMethodDispatcher.getProperty(image, "depth").toInt());
        assertEquals("image", ImageMethodDispatcher.getProperty(image, "ilk").toKeyName());

        Datum.Rect rect = (Datum.Rect) ImageMethodDispatcher.getProperty(image, "rect");
        assertEquals(0, rect.left());
        assertEquals(0, rect.top());
        assertEquals(0, rect.right());
        assertEquals(0, rect.bottom());
    }

    @Test
    void nullBackedImageMethodsAreNoOpsOrReturnEmptyImages() {
        Datum.ImageRef image = new Datum.ImageRef((Bitmap) null);

        assertEquals(0, ImageMethodDispatcher.dispatch(image, "getAt", List.of(Datum.of(1))).toInt());
        assertEquals(0, ImageMethodDispatcher.dispatch(image, "getAt", List.of(Datum.of(2))).toInt());
        ImageMethodDispatcher.dispatch(image, "copyPixels",
                List.of(new Datum.ImageRef((Bitmap) null),
                        new Datum.Rect(0, 0, 1, 1),
                        new Datum.Rect(0, 0, 1, 1)));

        Datum.ImageRef duplicate = (Datum.ImageRef) ImageMethodDispatcher.dispatch(
                image, "duplicate", List.of());
        assertEquals(0, duplicate.bitmap().getWidth());
        assertEquals(0, duplicate.bitmap().getHeight());
    }

    @Test
    void drawRectWithoutLineSizeUsesDirectorOutlineSemantics() {
        Bitmap bitmap = new Bitmap(4, 4, 32);
        bitmap.fill(0xFFFFFFFF);
        Datum.PropList props = new Datum.PropList();
        props.add("shapeType", Datum.symbol("rect"), true);
        props.add("color", new Datum.Color(1, 2, 3), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(bitmap), "draw",
                List.of(new Datum.Rect(0, 0, 4, 4), props));

        assertEquals(0xFF010203, bitmap.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, bitmap.getPixel(1, 1));
        assertEquals(0xFF010203, bitmap.getPixel(3, 3));
    }

    @Test
    void loadingBarFillThenDrawLeavesInteriorBackgroundVisible() {
        Bitmap bitmap = new Bitmap(128, 16, 32);
        bitmap.fill(0xFF000000);
        Datum.ImageRef image = new Datum.ImageRef(bitmap);
        Datum.Rect barRect = new Datum.Rect(0, 0, 128, 16);
        Datum.Color gray = new Datum.Color(128, 128, 128);
        Datum.Color black = new Datum.Color(0, 0, 0);
        Datum.PropList props = new Datum.PropList();
        props.add("shapeType", Datum.symbol("rect"), true);
        props.add("color", gray, true);

        ImageMethodDispatcher.dispatch(image, "fill", List.of(barRect, black));
        ImageMethodDispatcher.dispatch(image, "draw", List.of(barRect, props));

        assertEquals(0xFF808080, bitmap.getPixel(0, 0));
        assertEquals(0xFF000000, bitmap.getPixel(2, 2));
        assertEquals(0xFF000000, bitmap.getPixel(125, 13));
    }

    @Test
    void drawRectWithLineSizeKeepsOutlineSemantics() {
        Bitmap bitmap = new Bitmap(4, 4, 32);
        bitmap.fill(0xFFFFFFFF);
        Datum.PropList props = new Datum.PropList();
        props.add("shapeType", Datum.symbol("rect"), true);
        props.add("lineSize", Datum.of(1), true);
        props.add("color", new Datum.Color(1, 2, 3), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(bitmap), "draw",
                List.of(new Datum.Rect(0, 0, 4, 4), props));

        assertEquals(0xFF010203, bitmap.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, bitmap.getPixel(1, 1));
    }

    @Test
    void drawLineAllowsHorizontalAndVerticalZeroExtentRects() {
        Bitmap bitmap = new Bitmap(5, 5, 32);
        bitmap.fill(0xFFFFFFFF);
        Datum.PropList props = new Datum.PropList();
        props.add("shapeType", Datum.symbol("line"), true);
        props.add("color", new Datum.Color(0, 0, 0), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(bitmap), "draw",
                List.of(new Datum.Rect(1, 2, 4, 2), props));
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(bitmap), "draw",
                List.of(new Datum.Rect(3, 1, 3, 4), props));

        assertEquals(0xFF000000, bitmap.getPixel(1, 2));
        assertEquals(0xFF000000, bitmap.getPixel(4, 2));
        assertEquals(0xFF000000, bitmap.getPixel(3, 1));
        assertEquals(0xFF000000, bitmap.getPixel(3, 4));
        assertEquals(0xFFFFFFFF, bitmap.getPixel(0, 0));
    }

    @Test
    void drawPointToPointUsesLineSemantics() {
        Bitmap bitmap = new Bitmap(5, 5, 32);
        bitmap.fill(0xFFFFFFFF);
        Datum.PropList props = new Datum.PropList();
        props.add("color", new Datum.Color(0, 0, 0), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(bitmap), "draw",
                List.of(new Datum.Point(1, 3), new Datum.Point(4, 3), props));
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(bitmap), "draw",
                List.of(new Datum.Point(2, 1), new Datum.Point(2, 4), props));

        assertEquals(0xFF000000, bitmap.getPixel(1, 3));
        assertEquals(0xFF000000, bitmap.getPixel(4, 3));
        assertEquals(0xFF000000, bitmap.getPixel(2, 1));
        assertEquals(0xFF000000, bitmap.getPixel(2, 4));
        assertEquals(0xFFFFFFFF, bitmap.getPixel(0, 0));
    }

    @Test
    void copyPixelsDoesNotCarrySourceAnchorIntoDestinationImage() {
        Bitmap source = new Bitmap(10, 10, 32);
        source.fill(0xFFFFFFFF);
        source.fillRect(3, 4, 3, 2, 0xFF112233);
        source.setAnchorPoint(4, 5);

        Bitmap dest = new Bitmap(20, 20, 32);
        dest.fill(0xFFFFFFFF);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(source),
                        new Datum.Rect(7, 8, 17, 18),
                        new Datum.Rect(0, 0, 10, 10)));

        assertFalse(dest.hasAnchorPoint());

        Datum.ImageRef trimmed = (Datum.ImageRef) ImageMethodDispatcher.dispatch(
                new Datum.ImageRef(dest), "trimWhiteSpace", List.of());
        assertFalse(trimmed.bitmap().hasAnchorPoint());
    }

    @Test
    void cropPreservesAdjustedImageAnchor() {
        Bitmap source = new Bitmap(30, 20, 32);
        source.fill(0xFFFFFFFF);
        source.setAnchorPoint(15, 12);

        Datum.ImageRef cropped = (Datum.ImageRef) ImageMethodDispatcher.dispatch(
                new Datum.ImageRef(source),
                "crop",
                List.of(new Datum.Rect(5, 3, 25, 18)));

        assertTrue(cropped.bitmap().hasAnchorPoint());
        assertEquals(10, cropped.bitmap().getAnchorX());
        assertEquals(9, cropped.bitmap().getAnchorY());
    }

    @Test
    void trimWhiteSpacePreservesAdjustedImageAnchor() {
        Bitmap source = new Bitmap(30, 20, 32);
        source.fill(0xFFFFFFFF);
        source.fillRect(6, 4, 10, 8, 0xFF000000);
        source.setAnchorPoint(15, 12);

        Datum.ImageRef trimmed = (Datum.ImageRef) ImageMethodDispatcher.dispatch(
                new Datum.ImageRef(source), "trimWhiteSpace", List.of());

        assertTrue(trimmed.bitmap().hasAnchorPoint());
        assertEquals(9, trimmed.bitmap().getAnchorX());
        assertEquals(8, trimmed.bitmap().getAnchorY());
    }

    @Test
    void copyPixelsWithVoidSourceRectCopiesTheWholeSourceImage() {
        Bitmap source = new Bitmap(2, 2, 32);
        source.fill(0xFFFFFFFF);
        source.setPixel(1, 1, 0xFF123456);
        Bitmap dest = new Bitmap(2, 2, 32);
        dest.fill(0xFF000000);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(source), new Datum.Rect(0, 0, 2, 2), Datum.VOID));

        assertEquals(0xFFFFFFFF, dest.getPixel(0, 0));
        assertEquals(0xFF123456, dest.getPixel(1, 1));
    }

    @Test
    void copyPixelsLeavesDestinationOutsideSourceBoundsUntouched() {
        Bitmap source = new Bitmap(300, 4, 32);
        source.fill(0xFF224466);
        Bitmap dest = new Bitmap(312, 4, 32);
        dest.fill(0xFFFFFFFF);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(source),
                        new Datum.Rect(0, 0, 312, 4),
                        new Datum.Rect(0, 0, 312, 4)));

        assertEquals(0xFF224466, dest.getPixel(299, 0));
        assertEquals(0xFFFFFFFF, dest.getPixel(300, 0));
        assertEquals(0xFFFFFFFF, dest.getPixel(311, 3));
    }

    @Test
    void copyPixelsAcceptsBlendLevelByteScale() {
        Bitmap source = new Bitmap(1, 1, 32, new int[] {0xFF000000});
        Bitmap dest = new Bitmap(1, 1, 32, new int[] {0xFFFFFFFF});
        Datum.PropList props = new Datum.PropList();
        props.add("blendLevel", Datum.of(128), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(source), new Datum.Rect(0, 0, 1, 1),
                        new Datum.Rect(0, 0, 1, 1), props));

        assertEquals(0xFF7F7F7F, dest.getPixel(0, 0));
    }

    @Test
    void copyPixelsAddPinIgnoresTransparentPixels() {
        Bitmap source = new Bitmap(2, 1, 32, new int[] {
                0x00FFFFFF,
                0xFF204060
        });
        Bitmap dest = new Bitmap(2, 1, 32, new int[] {
                0xFF102030,
                0xFF102030
        });
        Datum.PropList props = new Datum.PropList();
        props.add("ink", Datum.of(33), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(source), new Datum.Rect(0, 0, 2, 1),
                        new Datum.Rect(0, 0, 2, 1), props));

        assertEquals(0xFF102030, dest.getPixel(0, 0),
                "Transparent matte pixels must not brighten the target under ADD_PIN");
        assertEquals(0xFF306090, dest.getPixel(1, 0));
    }

    @Test
    void copyPixelsAddPinUsesSourceAlpha() {
        Bitmap source = new Bitmap(1, 1, 32, new int[] {0x804080C0});
        Bitmap dest = new Bitmap(1, 1, 32, new int[] {0xFF204060});
        Datum.PropList props = new Datum.PropList();
        props.add("ink", Datum.of(33), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(source), new Datum.Rect(0, 0, 1, 1),
                        new Datum.Rect(0, 0, 1, 1), props));

        assertEquals(0xFF4080AF, dest.getPixel(0, 0),
                "ADD_PIN should interpolate the additive result by source alpha");
    }

    @Test
    void quadCopyPixelsAppliesTransformedMaskAndDarkenTint() {
        Bitmap dest = new Bitmap(2, 1, 32);
        dest.fill(0xFFFFFFFF);

        Bitmap src = new Bitmap(2, 1, 32, new int[] {
                0xFFC0C0C0,
                0xFF202020
        });
        Bitmap mask = new Bitmap(2, 1, 32, new int[] {
                0xFFFFFFFF,
                0x00FFFFFF
        });
        mask.setNativeAlpha(true);

        Datum.List flipQuad = new Datum.List(new ArrayList<>(List.of(
                new Datum.Point(2, 0),
                new Datum.Point(0, 0),
                new Datum.Point(0, 1),
                new Datum.Point(2, 1)
        )));
        Datum.PropList props = new Datum.PropList();
        props.add("ink", Datum.of(41), true);
        props.add("bgColor", new Datum.Color(0xEE, 0x7E, 0xA4), true);
        props.add("maskImage", new Datum.ImageRef(mask), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(src), flipQuad, new Datum.Rect(0, 0, 2, 1), props));

        assertEquals(0xFFFFFFFF, dest.getPixel(0, 0),
                "The transparent half of the transformed mask should leave the destination unchanged");
        assertEquals(0xFFB25E7B, dest.getPixel(1, 0),
                "Quad copies must reuse normal copyPixels color/tint handling after transforming source pixels");
    }

    @Test
    void skewedQuadCopyPixelsDoesNotFillBoundingBox() {
        Bitmap dest = new Bitmap(5, 4, 32);
        dest.fill(0xFF112233);

        Bitmap src = new Bitmap(3, 2, 32);
        src.fill(0xFFFF0000);

        Datum.List skewQuad = new Datum.List(new ArrayList<>(List.of(
                new Datum.Point(1, 0),
                new Datum.Point(4, 1),
                new Datum.Point(3, 3),
                new Datum.Point(0, 2)
        )));

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(src), skewQuad, new Datum.Rect(0, 0, 3, 2)));

        assertEquals(0xFF112233, dest.getPixel(0, 0),
                "Non-rectangular quad copies must leave pixels outside the quad untouched");
        assertEquals(0xFFFF0000, dest.getPixel(2, 1),
                "Pixels inside the skewed quad should still receive the transformed source");
    }

    @Test
    void copyPixelsPaletteAliasRemapsIndexedSourceLikePaletteRef() {
        Bitmap dest = new Bitmap(1, 1, 32);
        dest.fill(0xFFFFFFFF);

        Bitmap src = new Bitmap(1, 1, 8);
        src.setImagePalette(Palette.SYSTEM_MAC_PALETTE);
        src.setPixelPaletteIndex(0, 0, 255, 0xFFFF0000);

        Datum.PropList props = new Datum.PropList();
        props.add("palette", Datum.symbol("grayscale"), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(src), new Datum.Rect(0, 0, 1, 1),
                        new Datum.Rect(0, 0, 1, 1), props));

        assertEquals(0xFF000000, dest.getPixel(0, 0),
                "Director accepts #palette in copyPixels propLists, not only #paletteRef");
    }

    @Test
    void copyPixelsHonorsMaskOffset() {
        Bitmap dest = new Bitmap(2, 1, 32);
        dest.fill(0xFF000000);
        Bitmap src = new Bitmap(3, 1, 32, new int[] {
                0xFFFF0000,
                0xFF00FF00,
                0xFF0000FF
        });
        Bitmap mask = new Bitmap(3, 1, 32, new int[] {
                0x00FFFFFF,
                0xFFFFFFFF,
                0x00FFFFFF
        });
        mask.setNativeAlpha(true);

        Datum.PropList props = new Datum.PropList();
        props.add("maskImage", new Datum.ImageRef(mask), true);
        props.add("maskOffset", new Datum.Point(-1, 0), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(src), new Datum.Rect(0, 0, 2, 1),
                        new Datum.Rect(0, 0, 2, 1), props));

        assertEquals(0xFFFF0000, dest.getPixel(0, 0),
                "maskOffset shifts mask sampling relative to source coordinates");
        assertEquals(0xFF000000, dest.getPixel(1, 0),
                "pixels outside the shifted mask must stay untouched");
    }

    @Test
    void createMaskResultClipsCopyPixelsMaskImage() {
        Bitmap maskSource = new Bitmap(2, 1, 32, new int[] {
                0xFF000000,
                0xFFFFFFFF
        });
        Datum maskDatum = ImageMethodDispatcher.dispatch(new Datum.ImageRef(maskSource), "createMask", List.of());

        Bitmap dest = new Bitmap(2, 1, 32);
        dest.fill(0xFF000000);
        Bitmap src = new Bitmap(2, 1, 32, new int[] {
                0xFFFF0000,
                0xFF00FF00
        });
        Datum.PropList props = new Datum.PropList();
        props.add("maskImage", maskDatum, true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(src), new Datum.Rect(0, 0, 2, 1),
                        new Datum.Rect(0, 0, 2, 1), props));

        assertEquals(0xFFFF0000, dest.getPixel(0, 0));
        assertEquals(0xFF000000, dest.getPixel(1, 0),
                "white createMask pixels must block the copied source");
    }

    @Test
    void createMaskKeepsSoftLumaAsCopyOpacity() {
        Bitmap maskSource = new Bitmap(1, 1, 32, new int[] { 0xFF808080 });
        Datum maskDatum = ImageMethodDispatcher.dispatch(new Datum.ImageRef(maskSource), "createMask", List.of());

        Bitmap dest = new Bitmap(1, 1, 32, new int[] { 0xFF000000 });
        Bitmap src = new Bitmap(1, 1, 32, new int[] { 0xFFFF0000 });
        Datum.PropList props = new Datum.PropList();
        props.add("maskImage", maskDatum, true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(src), new Datum.Rect(0, 0, 1, 1),
                        new Datum.Rect(0, 0, 1, 1), props));

        assertEquals(0xFF7F0000, dest.getPixel(0, 0),
                "createMask luma should modulate opacity instead of acting as a binary clip");
    }

    @Test
    void copyPixelsMasksRemappedIntermediateUsingOriginalSourceCoordinates() {
        Bitmap dest = new Bitmap(2, 1, 32);
        dest.fill(0xFF000000);
        Bitmap src = new Bitmap(4, 1, 32, new int[] {
                0xFFFFFFFF,
                0xFFFFFFFF,
                0xFF000000,
                0xFF000000
        });
        Bitmap mask = new Bitmap(4, 1, 32, new int[] {
                0x00FFFFFF,
                0x00FFFFFF,
                0xFFFFFFFF,
                0x00FFFFFF
        });
        mask.setNativeAlpha(true);

        Datum.PropList props = new Datum.PropList();
        props.add("color", new Datum.Color(255, 0, 0), true);
        props.add("maskImage", new Datum.ImageRef(mask), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(src), new Datum.Rect(0, 0, 2, 1),
                        new Datum.Rect(2, 0, 4, 1), props));

        assertEquals(0xFFFF0000, dest.getPixel(0, 0),
                "mask sampling should stay in the original source coordinate space after remapping");
        assertEquals(0xFF000000, dest.getPixel(1, 0));
    }

    @Test
    void calendarCopyWithBackgroundTransparentPreservesDrawnGrid() {
        Bitmap calendar = new Bitmap(169, 145, 32);
        calendar.fill(0xFFFFFFFF);
        Datum.ImageRef calendarRef = new Datum.ImageRef(calendar);
        Datum.PropList drawProps = new Datum.PropList();
        drawProps.add("shapeType", Datum.symbol("rect"), true);
        drawProps.add("lineSize", Datum.of(1), true);
        drawProps.add("color", new Datum.Color(0, 0, 0), true);

        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 7; col++) {
                int left = col * 24;
                int top = row * 24;
                if (row == 0 && col == 1) {
                    calendar.fillRect(left, top + 11, 25, 2, 0xFF66BB22);
                }
                ImageMethodDispatcher.dispatch(calendarRef, "draw",
                        List.of(new Datum.Rect(left, top, left + 25, top + 25), drawProps));
            }
        }

        Bitmap selected = new Bitmap(169, 145, 32);
        selected.fill(0xFFFFFFFF);
        selected.fillRect(1, 1, 23, 23, 0xFFD3D3D3);
        Datum.PropList inkProps = new Datum.PropList();
        inkProps.add("ink", Datum.of(36), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(selected), "copyPixels",
                List.of(calendarRef, new Datum.Rect(0, 0, 169, 145),
                        new Datum.Rect(0, 0, 169, 145), inkProps));

        assertEquals(0xFF000000, selected.getPixel(24, 12),
                "ink 36 must key the white calendar background, not the drawn black grid");
        assertEquals(0xFF66BB22, selected.getPixel(30, 11),
                "event fills should still survive the calendar copy");
    }

    @Test
    void matteCopyOfScriptCalendarPreservesDrawnEdgeGrid() {
        Bitmap calendar = new Bitmap(169, 145, 32);
        calendar.fill(0xFFFFFFFF);
        calendar.markScriptModified();
        Datum.ImageRef calendarRef = new Datum.ImageRef(calendar);
        Datum.PropList drawProps = new Datum.PropList();
        drawProps.add("shapeType", Datum.symbol("rect"), true);
        drawProps.add("lineSize", Datum.of(1), true);
        drawProps.add("color", new Datum.Color(0, 0, 0), true);

        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 7; col++) {
                int left = col * 24;
                int top = row * 24;
                ImageMethodDispatcher.dispatch(calendarRef, "draw",
                        List.of(new Datum.Rect(left, top, left + 25, top + 25), drawProps));
            }
        }

        Bitmap windowBuffer = new Bitmap(169, 145, 32);
        windowBuffer.fill(0xFFFFFFFF);
        Datum.PropList matteProps = new Datum.PropList();
        matteProps.add("ink", Datum.of(8), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(windowBuffer), "copyPixels",
                List.of(calendarRef, new Datum.Rect(0, 0, 169, 145),
                        new Datum.Rect(0, 0, 169, 145), matteProps));

        assertEquals(0xFF000000, windowBuffer.getPixel(24, 12),
                "matte copy must not infer black edge grid lines as the scripted image background");
        assertEquals(0xFF000000, windowBuffer.getPixel(0, 0),
                "outer cell border is visible content, not the matte color for image() buffers");
    }

    @Test
    void backgroundTransparentDoesNotTreatMismatchedBgColorAsCalendarGridBackground() {
        Bitmap calendar = new Bitmap(169, 145, 32);
        calendar.fill(0xFFFFFFFF);
        Datum.ImageRef calendarRef = new Datum.ImageRef(calendar);
        Datum.PropList drawProps = new Datum.PropList();
        drawProps.add("shapeType", Datum.symbol("rect"), true);
        drawProps.add("lineSize", Datum.of(1), true);
        drawProps.add("color", new Datum.Color(0, 0, 0), true);

        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 7; col++) {
                int left = col * 24;
                int top = row * 24;
                if (row == 0 && col == 1) {
                    calendar.fillRect(left, top + 11, 25, 2, 0xFF66BB22);
                }
                ImageMethodDispatcher.dispatch(calendarRef, "draw",
                        List.of(new Datum.Rect(left, top, left + 25, top + 25), drawProps));
            }
        }

        Bitmap windowBuffer = new Bitmap(169, 145, 32);
        windowBuffer.fill(0xFFEFEFEF);
        Datum.PropList windowParams = new Datum.PropList();
        windowParams.add("ink", Datum.of(36), true);
        windowParams.add("bgColor", new Datum.Color(0, 0, 0), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(windowBuffer), "copyPixels",
                List.of(calendarRef, new Datum.Rect(0, 0, 169, 145),
                        new Datum.Rect(0, 0, 169, 145), windowParams));

        assertEquals(0xFF000000, windowBuffer.getPixel(24, 12),
                "a window bgColor must not make drawn black calendar grid lines transparent");
        assertEquals(0xFFEFEFEF, windowBuffer.getPixel(12, 12),
                "the real white calendar background should still key through to the window buffer");
        assertEquals(0xFF66BB22, windowBuffer.getPixel(30, 11),
                "colored event strips should remain opaque");
    }

    @Test
    void backgroundTransparentCopyUsesRenderedTextBackgroundAsKey() {
        Bitmap text = new Bitmap(4, 3, 32);
        text.fill(0xFF6794A7);
        text.setPixel(1, 1, 0xFFFFFFFF);
        text.setPixel(2, 1, 0xFFFFFFFF);
        text.markScriptModified();
        text.markTextRenderedImage(0xFF6794A7);

        Bitmap dest = new Bitmap(4, 3, 32);
        dest.fill(0xFF333333);
        Datum.PropList props = new Datum.PropList();
        props.add("ink", Datum.of(36), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(text), new Datum.Rect(0, 0, 4, 3),
                        new Datum.Rect(0, 0, 4, 3), props));

        assertEquals(0xFF333333, dest.getPixel(0, 0),
                "the text backing color is the ink 36 key for text-rendered image sources");
        assertEquals(0xFFFFFFFF, dest.getPixel(1, 1),
                "glyph pixels must still copy through unchanged");
    }

    @Test
    void backgroundTransparentHonorsExplicitBgColorWhenItMatchesTheSourceBackground() {
        Bitmap source = new Bitmap(4, 4, 32);
        source.fill(0xFF000000);
        source.fillRect(1, 1, 2, 2, 0xFFFF0000);
        Bitmap dest = new Bitmap(4, 4, 32);
        dest.fill(0xFF777777);
        Datum.PropList props = new Datum.PropList();
        props.add("ink", Datum.of(36), true);
        props.add("bgColor", new Datum.Color(0, 0, 0), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(source), new Datum.Rect(0, 0, 4, 4),
                        new Datum.Rect(0, 0, 4, 4), props));

        assertEquals(0xFF777777, dest.getPixel(0, 0),
                "explicit bgColor remains the transparent key when it is the source background");
        assertEquals(0xFFFF0000, dest.getPixel(1, 1));
    }

    @Test
    void backgroundTransparentDoesNotRecolorAlreadyRenderedTextWithExplicitBgColor() {
        Bitmap source = new Bitmap(8, 4, 32);
        source.fill(0xFF6A6A6A);
        source.setPixel(3, 1, 0xFFFCFCFC);
        source.setPixel(4, 1, 0xFFFCFCFC);

        Bitmap dest = new Bitmap(8, 4, 32);
        dest.fill(0xFF222222);
        Datum.PropList props = new Datum.PropList();
        props.add("ink", Datum.of(36), true);
        props.add("color", new Datum.Color(0xFC, 0xFC, 0xFC), true);
        props.add("bgColor", new Datum.Color(0x6A, 0x6A, 0x6A), true);

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(source), new Datum.Rect(0, 0, 8, 4),
                        new Datum.Rect(0, 0, 8, 4), props));

        assertEquals(0xFF222222, dest.getPixel(0, 0),
                "the explicit source background should key out instead of being recolored");
        assertEquals(0xFFFCFCFC, dest.getPixel(3, 1),
                "already rendered white glyph pixels should copy unchanged");
    }
}
