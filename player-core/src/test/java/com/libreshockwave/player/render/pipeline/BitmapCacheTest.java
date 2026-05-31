package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.id.InkMode;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.opcode.dispatch.ImageMethodDispatcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class BitmapCacheTest {

    private static final byte[] EXIT_SHAPE_SPECIFIC_DATA = new byte[] {
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x39,
            0x00, 0x39,
            0x00, 0x01,
            (byte) 0xF9,
            0x00,
            0x00,
            0x01,
            0x05
    };

    @Test
    void indexedMatteRemapSkipsDefaultBlackToWhiteRamp() {
        Bitmap raw = new Bitmap(3, 1, 8, new int[] {
                0xFFFFFFFF,
                0xFF7B5005,
                0xFF000000
        });
        raw.setPaletteIndices(new byte[] {0, (byte) 128, (byte) 255});

        BitmapCache.IndexedMatteColorRemap remap = BitmapCache.resolveIndexedMatteColorRemap(
                raw, InkMode.MATTE.code(), 0x000000, 0xFFFFFF, true, true, null);

        assertNull(remap);
    }

    @Test
    void indexedMatteRemapUsesDirectForeColorAndResolvedBackColor() {
        Bitmap raw = new Bitmap(3, 1, 8, new int[] {
                0xFFFFFFFF,
                0xFF7B5005,
                0xFF000000
        });
        raw.setPaletteIndices(new byte[] {0, (byte) 128, (byte) 255});
        Palette palette = new Palette(new int[] {
                0xFFFFFFFF,
                0xFF33CC66
        }, "test-remap");

        BitmapCache.IndexedMatteColorRemap remap = BitmapCache.resolveIndexedMatteColorRemap(
                raw, InkMode.MATTE.code(), 0x000000, 1, true, true, palette);

        assertNotNull(remap);
        assertEquals(0x000000, remap.foreColor());
        assertEquals(0x33CC66, remap.backColor());
    }

    @Test
    void indexedBackgroundTransparentDoesNotRequestPaletteRampRemap() {
        Bitmap raw = new Bitmap(3, 1, 8, new int[] {
                0xFFFFFFFF,
                0xFF7B5005,
                0xFF000000
        });
        raw.setPaletteIndices(new byte[] {0, (byte) 128, (byte) 255});
        Palette palette = new Palette(new int[] {
                0xFFFFFFFF,
                0xFF6699FF
        }, "test-remap");

        BitmapCache.IndexedMatteColorRemap remap = BitmapCache.resolveIndexedMatteColorRemap(
                raw, InkMode.BACKGROUND_TRANSPARENT.code(), 0x000000, 1, true, true, palette);

        assertNull(remap);
    }

    @Test
    void backgroundTransparentKeepsIndexedSurvivorColorsWhenSpriteHasForeAndBackColor() {
        Bitmap raw = new Bitmap(3, 1, 8, new int[] {
                0xFFFFFFFF,
                0xFF336699,
                0xFF000000
        });
        raw.setPaletteIndices(new byte[] {0, (byte) 128, (byte) 255});
        CastMember member = new CastMember(1, 10005, MemberType.BITMAP);
        member.setBitmapDirectly(raw);

        Bitmap processed = new BitmapCache().getProcessedDynamic(
                member, InkMode.BACKGROUND_TRANSPARENT.code(), 0xFFFFFF,
                0xCC0000, true, true);

        assertNotNull(processed);
        assertEquals(0x00000000, processed.getPixel(0, 0),
                "backColor is only the transparent key for ink 36");
        assertEquals(0xFF336699, processed.getPixel(1, 0),
                "surviving indexed pixels must keep their source color");
        assertEquals(0xFF000000, processed.getPixel(2, 0));
    }

    @Test
    void backgroundTransparentScriptTextImageKeysItsRenderedBackground() {
        Bitmap raw = new Bitmap(3, 1, 32, new int[] {
                0xFF6794A7,
                0xFFFFFFFF,
                0xFF6794A7
        });
        raw.markScriptModified();
        raw.markTextRenderedImage(0xFF6794A7);
        CastMember member = new CastMember(1, 10006, MemberType.BITMAP);
        member.setBitmapDirectly(raw);

        Bitmap processed = new BitmapCache().getProcessedScriptModifiedDynamic(
                member, InkMode.BACKGROUND_TRANSPARENT.code(), 0xFFFFFF,
                0, false, false, false);

        assertNotNull(processed);
        assertEquals(0x00000000, processed.getPixel(0, 0),
                "script-rendered text images use their own backing color as ink 36 key");
        assertEquals(0xFFFFFFFF, processed.getPixel(1, 0));
        assertEquals(0x00000000, processed.getPixel(2, 0));
    }

    @Test
    void nonNativeThirtyTwoBitMemberAlphaIsRenderedOpaque() {
        Bitmap raw = new Bitmap(1, 1, 32, new int[] {0x00F0F0F0});

        Bitmap coerced = BitmapCache.coerceNonNativeAlphaToOpaque(raw, false);

        assertEquals(0xFFF0F0F0, coerced.getPixel(0, 0));
    }

    @Test
    void nativeThirtyTwoBitMemberAlphaIsPreserved() {
        Bitmap raw = new Bitmap(1, 1, 32, new int[] {0x00F0F0F0});
        raw.setNativeAlpha(true);

        Bitmap coerced = BitmapCache.coerceNonNativeAlphaToOpaque(raw, true);

        assertSame(raw, coerced);
        assertEquals(0x00F0F0F0, coerced.getPixel(0, 0));
    }

    @Test
    void scriptModifiedDynamicBitmapsAreCachedUntilMutatedAgain() {
        Bitmap bitmap = new Bitmap(1, 1, 32, new int[] {0xFFFFFFFF});
        bitmap.markScriptModified();
        CastMember member = new CastMember(1, 10001, MemberType.BITMAP);
        member.setBitmapDirectly(bitmap);

        BitmapCache cache = new BitmapCache();

        Bitmap first = cache.getProcessedScriptModifiedDynamic(
                member, InkMode.BACKGROUND_TRANSPARENT.code(), 0xFFFFFF,
                0, false, false, false);
        Bitmap second = cache.getProcessedScriptModifiedDynamic(
                member, InkMode.BACKGROUND_TRANSPARENT.code(), 0xFFFFFF,
                0, false, false, false);

        assertSame(first, second);

        bitmap.setPixel(0, 0, 0xFF000000);
        bitmap.markScriptModified();

        Bitmap afterMutation = cache.getProcessedScriptModifiedDynamic(
                member, InkMode.BACKGROUND_TRANSPARENT.code(), 0xFFFFFF,
                0, false, false, false);

        assertNotSame(first, afterMutation);
    }

    @Test
    void scriptModifiedDynamicCacheInvalidatesWhenPaletteStateChanges() {
        Palette bluePalette = new Palette(new int[] {0x0000FF}, "blue");
        Palette redPalette = new Palette(new int[] {0xFF0000}, "red");
        Bitmap bitmap = new Bitmap(1, 1, 8, new int[] {0xFF0000FF});
        bitmap.setImagePalette(bluePalette);
        bitmap.setPaletteIndices(new byte[] {0});
        bitmap.markScriptModified();
        CastMember member = new CastMember(1, 10003, MemberType.BITMAP);
        member.setBitmapDirectly(bitmap);

        BitmapCache cache = new BitmapCache();

        Bitmap first = cache.getProcessedScriptModifiedDynamic(
                member, InkMode.BACKGROUND_TRANSPARENT.code(), 0xFFFFFF,
                0, false, false, false);

        int revisionBeforePaletteChange = bitmap.getMutationRevision();
        assertEquals(1, bitmap.remapImagePalette(redPalette));
        assertEquals(revisionBeforePaletteChange, bitmap.getMutationRevision(),
                "palette remaps can change visible pixels without going through image mutation dispatch");

        Bitmap afterPaletteChange = cache.getProcessedScriptModifiedDynamic(
                member, InkMode.BACKGROUND_TRANSPARENT.code(), 0xFFFFFF,
                0, false, false, false);

        assertNotSame(first, afterPaletteChange);
        assertEquals(0xFFFF0000, afterPaletteChange.getPixel(0, 0));
    }

    @Test
    void drawnCalendarGridSurvivesMatteWindowElementInk() {
        Bitmap calendar = new Bitmap(169, 145, 32);
        calendar.fill(0xFFFFFFFF);
        Datum.PropList drawProps = new Datum.PropList();
        drawProps.add(new Datum.Symbol("shapeType"), new Datum.Symbol("rect"));
        drawProps.add(new Datum.Symbol("lineSize"), Datum.of(1));
        drawProps.add(new Datum.Symbol("color"), new Datum.Color(0, 0, 0));

        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 7; col++) {
                int left = col * 24;
                int top = row * 24;
                ImageMethodDispatcher.dispatch(new Datum.ImageRef(calendar), "draw",
                        List.of(new Datum.Rect(left, top, left + 25, top + 25), drawProps));
            }
        }

        CastMember member = new CastMember(1, 10002, MemberType.BITMAP);
        member.setBitmapDirectly(calendar);
        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 169, 145, 0, true,
                RenderSprite.SpriteType.BITMAP,
                null, member,
                0x000000, 0xFFFFFF, false, true,
                InkMode.MATTE.code(), 100, false, false, null, false
        );

        RenderSprite baked = new SpriteBaker(new BitmapCache(), null, null).bake(sprite);

        assertNotNull(baked.getBakedBitmap());
        assertEquals(0xFF000000, baked.getBakedBitmap().getPixel(24, 12),
                "calendar grid lines drawn by image.draw(rect, props) must be present before matte processing");
        assertEquals(0xFF000000, baked.getBakedBitmap().getPixel(168, 12),
                "the rightmost calendar border must survive rect drawing and matte processing");
        assertEquals(0xFFFFFFFF, baked.getBakedBitmap().getPixel(12, 12),
                "matte must not flood through the outer drawn border and erase cell backgrounds");
    }

    @Test
    void drawnCalendarGridSurvivesBackgroundTransparentWindowElementInk() {
        Bitmap calendar = new Bitmap(169, 145, 32);
        calendar.fill(0xFFFFFFFF);
        Datum.PropList drawProps = new Datum.PropList();
        drawProps.add(new Datum.Symbol("shapeType"), new Datum.Symbol("rect"));
        drawProps.add(new Datum.Symbol("lineSize"), Datum.of(1));
        drawProps.add(new Datum.Symbol("color"), new Datum.Color(0, 0, 0));

        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 7; col++) {
                int left = col * 24;
                int top = row * 24;
                if (row == 0 && col == 1) {
                    calendar.fillRect(left, top + 11, 25, 2, 0xFF66BB22);
                }
                ImageMethodDispatcher.dispatch(new Datum.ImageRef(calendar), "draw",
                        List.of(new Datum.Rect(left, top, left + 25, top + 25), drawProps));
            }
        }

        CastMember member = new CastMember(1, 10003, MemberType.BITMAP);
        member.setBitmapDirectly(calendar);
        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 169, 145, 0, true,
                RenderSprite.SpriteType.BITMAP,
                null, member,
                0x000000, 0x000000, false, true,
                InkMode.BACKGROUND_TRANSPARENT.code(), 100, false, false, null, false
        );

        RenderSprite baked = new SpriteBaker(new BitmapCache(), null, null).bake(sprite);

        assertNotNull(baked.getBakedBitmap());
        assertEquals(0xFF000000, baked.getBakedBitmap().getPixel(24, 12),
                "ink 36 on a 32-bit script image must remove white, not drawn black grid lines");
        assertEquals(0xFF000000, baked.getBakedBitmap().getPixel(168, 12),
                "the final right calendar border must not be keyed out by ink 36");
        assertEquals(0xFF66BB22, baked.getBakedBitmap().getPixel(30, 11),
                "event fills should remain visible under window-element ink");
    }

    @Test
    void drawnCalendarGridSurvivesCopiedSelectionAndBackgroundTransparentWindowElementInk() {
        Bitmap calendar = new Bitmap(169, 145, 32);
        calendar.fill(0xFFFFFFFF);
        Datum.PropList drawProps = new Datum.PropList();
        drawProps.add(new Datum.Symbol("shapeType"), new Datum.Symbol("rect"));
        drawProps.add(new Datum.Symbol("lineSize"), Datum.of(1));
        drawProps.add(new Datum.Symbol("color"), new Datum.Color(0, 0, 0));

        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 7; col++) {
                int left = col * 24;
                int top = row * 24;
                if (row == 0 && col == 1) {
                    calendar.fillRect(left, top + 11, 25, 2, 0xFF66BB22);
                }
                ImageMethodDispatcher.dispatch(new Datum.ImageRef(calendar), "draw",
                        List.of(new Datum.Rect(left, top, left + 25, top + 25), drawProps));
            }
        }

        Bitmap selected = new Bitmap(169, 145, 32);
        selected.fill(0xFFFFFFFF);
        selected.fillRect(1, 1, 23, 23, 0xFFD3D3D3);
        Datum.PropList inkProps = new Datum.PropList();
        inkProps.add(new Datum.Symbol("ink"), Datum.of(36));
        ImageMethodDispatcher.dispatch(new Datum.ImageRef(selected), "copyPixels",
                List.of(new Datum.ImageRef(calendar), new Datum.Rect(0, 0, 169, 145),
                        new Datum.Rect(0, 0, 169, 145), inkProps));

        CastMember member = new CastMember(1, 10004, MemberType.BITMAP);
        member.setBitmapDirectly(selected);
        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 169, 145, 0, true,
                RenderSprite.SpriteType.BITMAP,
                null, member,
                0x000000, 0x000000, false, true,
                InkMode.BACKGROUND_TRANSPARENT.code(), 100, false, false, null, false
        );

        RenderSprite baked = new SpriteBaker(new BitmapCache(), null, null).bake(sprite);

        assertNotNull(baked.getBakedBitmap());
        assertEquals(0xFF000000, baked.getBakedBitmap().getPixel(24, 12),
                "grid lines must survive both the Lingo selection copy and the final sprite ink");
        assertEquals(0xFF000000, baked.getBakedBitmap().getPixel(168, 12),
                "the right calendar border must survive both the Lingo copy and final sprite ink");
        assertEquals(0xFFD3D3D3, baked.getBakedBitmap().getPixel(12, 12),
                "the selected cell fill should remain after final baking");
    }

    @Test
    void quadCopiedPalettedTargetKeepsIndicesAndDynamicMatteRemap() {
        Palette sourcePalette = new Palette(new int[] {0xFFFFFF, 0xFF808080, 0xFF000000}, "shade-ramp");
        Bitmap src = new Bitmap(2, 3, 8, new int[] {
                0xFFFFFFFF, 0xFF000000,
                0xFF808080, 0xFF000000,
                0xFFFFFFFF, 0xFF808080
        });
        src.setImagePalette(sourcePalette);
        src.setPaletteRefCastMember(4, 12);
        src.setPaletteIndices(new byte[] {
                0, (byte) 255,
                (byte) 128, (byte) 255,
                0, (byte) 128
        });

        Bitmap dest = new Bitmap(3, 2, 8);
        Datum.List quad = new Datum.List(new ArrayList<>(List.of(
                new Datum.Point(3, 0),
                new Datum.Point(3, 2),
                new Datum.Point(0, 2),
                new Datum.Point(0, 0)
        )));

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(src), quad, new Datum.Rect(0, 0, 2, 3)));

        assertSame(sourcePalette, dest.getImagePalette());
        assertEquals(4, dest.getPaletteRefCastLib());
        assertEquals(12, dest.getPaletteRefMemberNum());
        assertArrayEquals(new byte[] {
                0, (byte) 128, 0,
                (byte) 128, (byte) 255, (byte) 255
        }, dest.getPaletteIndices());

        CastMember member = new CastMember(1, 10005, MemberType.BITMAP);
        member.setBitmapDirectly(dest);

        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 3, 2, 0, true,
                RenderSprite.SpriteType.BITMAP,
                null, member,
                0x000000, 0x33CC66, true, true,
                8, 100, false, false, null, false
        );

        SpriteBaker baker = new SpriteBaker(new BitmapCache(), null, null);
        RenderSprite baked = baker.bake(sprite);

        assertNotNull(baked.getBakedBitmap());
        assertEquals(0x00000000, baked.getBakedBitmap().getPixel(0, 0));
        assertEquals(0xFF196633, baked.getBakedBitmap().getPixel(1, 0));
        assertEquals(0x00000000, baked.getBakedBitmap().getPixel(2, 0));
        assertEquals(0xFF196633, baked.getBakedBitmap().getPixel(0, 1));
        assertEquals(0xFF000000, baked.getBakedBitmap().getPixel(1, 1));
        assertEquals(0xFF000000, baked.getBakedBitmap().getPixel(2, 1));
    }

    @Test
    void quadCopiedPalettedSourceDoesNotMakeRgbTargetIndexed() {
        Palette sourcePalette = new Palette(new int[] {0xFFFFFF, 0xFF808080, 0xFF000000}, "shade-ramp");
        Bitmap src = new Bitmap(2, 3, 8, new int[] {
                0xFFFFFFFF, 0xFF000000,
                0xFF808080, 0xFF000000,
                0xFFFFFFFF, 0xFF808080
        });
        src.setImagePalette(sourcePalette);
        src.setPaletteIndices(new byte[] {
                0, (byte) 255,
                (byte) 128, (byte) 255,
                0, (byte) 128
        });

        Bitmap dest = new Bitmap(3, 2, 32);
        Datum.List quad = new Datum.List(new ArrayList<>(List.of(
                new Datum.Point(3, 0),
                new Datum.Point(3, 2),
                new Datum.Point(0, 2),
                new Datum.Point(0, 0)
        )));

        ImageMethodDispatcher.dispatch(new Datum.ImageRef(dest), "copyPixels",
                List.of(new Datum.ImageRef(src), quad, new Datum.Rect(0, 0, 2, 3)));

        assertNull(dest.getImagePalette());
        assertNull(dest.getPaletteIndices());

        CastMember member = new CastMember(1, 10006, MemberType.BITMAP);
        member.setBitmapDirectly(dest);

        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 3, 2, 0, true,
                RenderSprite.SpriteType.BITMAP,
                null, member,
                0x000000, 0x33CC66, true, true,
                8, 100, false, false, null, false
        );

        SpriteBaker baker = new SpriteBaker(new BitmapCache(), null, null);
        RenderSprite baked = baker.bake(sprite);

        assertNotNull(baked.getBakedBitmap());
        assertEquals(0x00000000, baked.getBakedBitmap().getPixel(0, 0));
        assertEquals(0xFF808080, baked.getBakedBitmap().getPixel(1, 0));
        assertEquals(0x00000000, baked.getBakedBitmap().getPixel(2, 0));
        assertEquals(0xFF808080, baked.getBakedBitmap().getPixel(0, 1));
        assertEquals(0xFF000000, baked.getBakedBitmap().getPixel(1, 1));
        assertEquals(0xFF000000, baked.getBakedBitmap().getPixel(2, 1));
    }

    @Test
    void authoredOutlineShapeWithThicknessOneBakesTransparent() {
        CastMemberChunk exitShape = new CastMemberChunk(
                null,
                new ChunkId(1),
                MemberType.SHAPE,
                0,
                EXIT_SHAPE_SPECIFIC_DATA.length,
                new byte[0],
                EXIT_SHAPE_SPECIFIC_DATA,
                "exitshape",
                0,
                0,
                0
        );

        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 57, 57, 0, true,
                RenderSprite.SpriteType.SHAPE,
                exitShape, null,
                0x888888, 0xFFFFFF, true, true,
                0, 100, false, false, null, false
        );

        SpriteBaker baker = new SpriteBaker(new BitmapCache(), null, null);
        RenderSprite baked = baker.bake(sprite);

        assertNotNull(baked.getBakedBitmap());
        assertEquals(0x00000000, baked.getBakedBitmap().getPixel(0, 0));
        assertEquals(0x00000000, baked.getBakedBitmap().getPixel(28, 28));
        assertEquals(0x00000000, baked.getBakedBitmap().getPixel(56, 56));
    }

    @Test
    void syntheticShapeWithoutMemberStillBakesSolidFill() {
        RenderSprite sprite = new RenderSprite(
                1, 0, 0, 4, 3, 0, true,
                RenderSprite.SpriteType.SHAPE,
                null, null,
                0x336699, 0xFFFFFF, true, true,
                0, 100, false, false, null, false
        );

        SpriteBaker baker = new SpriteBaker(new BitmapCache(), null, null);
        RenderSprite baked = baker.bake(sprite);

        assertNotNull(baked.getBakedBitmap());
        assertEquals(0xFF336699, baked.getBakedBitmap().getPixel(0, 0));
        assertEquals(0xFF336699, baked.getBakedBitmap().getPixel(2, 1));
        assertEquals(0xFF336699, baked.getBakedBitmap().getPixel(3, 2));
    }
}
