package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.id.InkMode;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.sprite.SpriteColorSource;
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
                raw, InkMode.MATTE.code(),
                0x000000, SpriteColorSource.RGB,
                0xFFFFFF, SpriteColorSource.RGB,
                true, true, null);

        assertNull(remap);
    }

    @Test
    void indexedMatteScriptBackColorResolvesAsDirectPaletteIndex() {
        Bitmap raw = new Bitmap(3, 1, 8, new int[] {
                0xFFFFFFFF,
                0xFFCCCCCC,
                0xFF000000
        });
        raw.setPaletteIndices(new byte[] {0, (byte) 128, (byte) 255});
        int[] colors = new int[256];
        colors[0] = 0xFFFFFF;
        colors[128] = 0xCCCCCC;
        colors[255] = 0x000000;
        Palette palette = new Palette(colors, "sprite-colors");

        BitmapCache.IndexedMatteColorRemap remap = BitmapCache.resolveIndexedMatteColorRemap(
                raw, InkMode.MATTE.code(),
                0x000000, SpriteColorSource.RGB,
                0, SpriteColorSource.PALETTE_INDEX,
                false, true, palette);

        assertNull(remap, "script-set bgColor paletteIndex(0) resolves to white");
    }

    @Test
    void indexedMatteWithSpriteBackColorKeepsBlackOutlineTouchingEdge() {
        Bitmap raw = new Bitmap(5, 5, 8, new int[] {
                0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000,
                0xFF000000, 0xFFFF6600, 0xFFFF6600, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFF000000, 0xFFFF6600, 0xFFFF6600, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFF000000, 0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF
        });
        raw.setPaletteIndices(new byte[] {
                (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255,
                (byte) 255, 64, 64, 0, 0,
                (byte) 255, 64, 64, 0, 0,
                (byte) 255, 0, 0, 0, 0,
                (byte) 255, (byte) 255, (byte) 255, 0, 0
        });
        int[] colors = new int[256];
        colors[0] = 0xFFFFFF;
        colors[64] = 0xFF6600;
        colors[255] = 0x000000;
        raw.setImagePalette(new Palette(colors, "director-colors"));
        CastMember member = new CastMember(1, 10007, MemberType.BITMAP);
        member.setBitmapDirectly(raw);

        Bitmap processed = new BitmapCache().getProcessedDynamic(
                member, InkMode.MATTE.code(), 0, SpriteColorSource.PALETTE_INDEX,
                0, SpriteColorSource.RGB, false, true);

        assertNotNull(processed);
        assertEquals(0xFF000000, processed.getPixel(0, 0),
                "explicit sprite bgColor must not key out black outline pixels");
        assertEquals(0xFFFF6600, processed.getPixel(1, 1),
                "visible artwork pixels must survive matte processing");
        assertEquals(0x00000000, processed.getPixel(4, 1),
                "script-set bgColor paletteIndex(0) keys white as the matte");
        assertEquals(0x00000000, processed.getPixel(4, 4),
                "edge-connected white backing should be transparent");
    }

    @Test
    void indexedMatteFallsBackWhenExplicitBackColorIsNotOnEdge() {
        Bitmap raw = new Bitmap(1, 1, 8, new int[] {0xFF000000});
        raw.setPaletteIndices(new byte[] {(byte) 255});
        int[] colors = new int[256];
        colors[0] = 0xFFFFFF;
        colors[255] = 0x000000;
        raw.setImagePalette(new Palette(colors, "director-colors"));
        CastMember member = new CastMember(1, 10017, MemberType.BITMAP);
        member.setBitmapDirectly(raw);

        Bitmap processed = new BitmapCache().getProcessedDynamic(
                member, InkMode.MATTE.code(), 0, SpriteColorSource.PALETTE_INDEX,
                0, SpriteColorSource.RGB, false, true);

        assertNotNull(processed);
        assertEquals(0x00000000, processed.getPixel(0, 0),
                "a script bgColor that is absent from the edge must not block inferred matte removal");
    }

    @Test
    void sameNumericBackColorDoesNotShareMatteCacheAcrossColorSources() {
        Bitmap raw = new Bitmap(3, 1, 8, new int[] {
                0xFFFFFFFF,
                0xFFFF6600,
                0xFF000000
        });
        raw.setPaletteIndices(new byte[] {0, 64, (byte) 255});
        int[] colors = new int[256];
        colors[0] = 0xFFFFFF;
        colors[64] = 0xFF6600;
        colors[255] = 0x000000;
        raw.setImagePalette(new Palette(colors, "director-colors"));
        CastMember member = new CastMember(1, 10008, MemberType.BITMAP);
        member.setBitmapDirectly(raw);

        BitmapCache cache = new BitmapCache();
        Bitmap directPaletteIndex = cache.getProcessedDynamic(
                member, InkMode.MATTE.code(), 0, SpriteColorSource.PALETTE_INDEX,
                0, SpriteColorSource.RGB, false, true);
        Bitmap directorColorNumber = cache.getProcessedDynamic(
                member, InkMode.MATTE.code(), 0, SpriteColorSource.DIRECTOR_COLOR_NUMBER,
                0, SpriteColorSource.RGB, false, true);

        assertNotNull(directPaletteIndex);
        assertNotNull(directorColorNumber);
        assertEquals(0x00000000, directPaletteIndex.getPixel(0, 0),
                "paletteIndex(0) keys palette slot 0, the white matte");
        assertEquals(0xFF000000, directPaletteIndex.getPixel(2, 0),
                "paletteIndex(0) must not key the black edge");
        assertEquals(0xFF000000, directorColorNumber.getPixel(0, 0),
                "Director color-number 0 is distinct from paletteIndex(0) and does not key palette slot 0");
        assertEquals(0x00000000, directorColorNumber.getPixel(2, 0),
                "Director color-number 0 keys palette slot 255");
    }

    @Test
    void scoreBackColorZeroKeysPaletteZeroWhiteMatte() {
        Bitmap raw = new Bitmap(3, 1, 8, new int[] {
                0xFFFFFFFF,
                0xFFFF6600,
                0xFF000000
        });
        raw.setPaletteIndices(new byte[] {0, 64, (byte) 255});
        int[] colors = new int[256];
        colors[0] = 0xFFFFFF;
        colors[64] = 0xFF6600;
        colors[255] = 0x000000;
        raw.setImagePalette(new Palette(colors, "score-colors"));
        CastMember member = new CastMember(1, 10009, MemberType.BITMAP);
        member.setBitmapDirectly(raw);

        Bitmap processed = new BitmapCache().getProcessedDynamic(
                member, InkMode.MATTE.code(), 0, SpriteColorSource.forScoreColor(false),
                0, SpriteColorSource.forScoreColor(false), false, true);

        assertNotNull(processed);
        assertEquals(0x00000000, processed.getPixel(0, 0),
                "score backColor 0 keys palette slot 0, the white matte backing");
        assertEquals(0xFFFF6600, processed.getPixel(1, 0));
        assertEquals(0xFF000000, processed.getPixel(2, 0),
                "score backColor 0 must not erase black outline/content pixels");
    }

    @Test
    void indexedMatteRemapUsesScriptBackColorPaletteIndex() {
        Bitmap raw = new Bitmap(3, 1, 8, new int[] {
                0xFFFFFFFF,
                0xFF7B5005,
                0xFF000000
        });
        raw.setPaletteIndices(new byte[] {0, (byte) 128, (byte) 255});
        int[] colors = new int[256];
        colors[0] = 0xFFFFFF;
        colors[1] = 0x33CC66;
        colors[255] = 0x000000;
        Palette palette = new Palette(colors, "test-remap");

        BitmapCache.IndexedMatteColorRemap remap = BitmapCache.resolveIndexedMatteColorRemap(
                raw, InkMode.MATTE.code(),
                0x000000, SpriteColorSource.RGB,
                1, SpriteColorSource.PALETTE_INDEX,
                true, true, palette);

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
    void implicitIndexedBackgroundTransparentFallsBackToEdgePaletteMatte() {
        int[] paletteColors = new int[256];
        paletteColors[0] = 0xFFFFFFFF;
        paletteColors[1] = 0xFF66CC44;
        paletteColors[255] = 0xFF000000;
        Palette palette = new Palette(paletteColors, "indexed-bg");

        Bitmap raw = new Bitmap(3, 3, 8, new int[] {
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFF66CC44, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF
        });
        raw.setImagePalette(palette);
        raw.setPaletteIndices(new byte[] {
                0, 0, 0,
                0, 1, 0,
                0, 0, 0
        });
        CastMember member = new CastMember(1, 10007, MemberType.BITMAP);
        member.setBitmapDirectly(raw);

        Bitmap processed = new BitmapCache().getProcessedDynamic(
                member, InkMode.BACKGROUND_TRANSPARENT.code(), 255,
                0, false, false);

        assertNotNull(processed);
        assertEquals(0x00000000, processed.getPixel(0, 0),
                "implicit indexed ink 36 must remove the authored edge matte");
        assertEquals(0xFF66CC44, processed.getPixel(1, 1),
                "non-matte pixels must keep their original color");
    }

    @Test
    void indexedOnePixelMatteSourceIsFullyTransparent() {
        Bitmap raw = new Bitmap(1, 1, 8, new int[] {0xFF000000});
        raw.setPaletteIndices(new byte[] {(byte) 255});

        Bitmap processed = InkProcessor.applyInk(raw, InkMode.MATTE, 0, false, raw.getImagePalette());

        assertEquals(0x00000000, processed.getPixel(0, 0),
                "a one-pixel matte bitmap is entirely edge-connected and should disappear");
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
                0x000000, 0x33CC66,
                SpriteColorSource.RGB, SpriteColorSource.RGB,
                true, true,
                8, 100, false, false,
                0.0, 0.0, null, false
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
