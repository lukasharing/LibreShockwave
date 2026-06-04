package com.libreshockwave.player.render.output;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.render.pipeline.FrameSnapshot;
import com.libreshockwave.player.render.pipeline.RenderPipelineTrace;
import com.libreshockwave.player.render.pipeline.RenderSprite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SoftwareFrameRendererTransformTest {

    @Test
    void spriteBlendUsesPercentCompositingOverOpaqueStage() {
        assertBlackThirtyPercentBlend(0x668085, 0xFF475A5D);
    }

    @Test
    void spriteBlendUsesDirectorPercentRoundingForHalfFractions() {
        assertBlackThirtyPercentBlend(0x005500, 0xFF003B00);
    }

    @Test
    void spriteBlendUsesDirectorFixedPointOpacityForNavigatorShadowColors() {
        assertBlackThirtyPercentBlend(0x53686C, 0xFF3A494B);
        assertBlackThirtyPercentBlend(0x517900, 0xFF385500);
    }

    private static void assertBlackThirtyPercentBlend(int backgroundColor, int expectedColor) {
        Bitmap src = new Bitmap(1, 1, 32, new int[]{
                0xFF000000
        });
        RenderSprite sprite = new RenderSprite(
                1,
                0, 0,
                1, 1,
                0,
                true,
                RenderSprite.SpriteType.BITMAP,
                null,
                null,
                0, 0xFFFFFF,
                false, false,
                0, 30,
                false, false,
                0.0, 0.0,
                src,
                false
        );

        Bitmap rendered = new FrameSnapshot(
                1, 1, 1, backgroundColor,
                List.of(sprite),
                "",
                null,
                0,
                RenderPipelineTrace.EMPTY
        ).renderFrame();

        assertEquals(expectedColor, rendered.getPixel(0, 0));
    }

    @Test
    void directorMirrorTransformFlipsSpriteHorizontally() {
        Bitmap src = new Bitmap(2, 1, 32, new int[]{
                0xFFFF0000,
                0xFF0000FF
        });
        RenderSprite sprite = new RenderSprite(
                1,
                0, 0,
                2, 1,
                0,
                true,
                RenderSprite.SpriteType.BITMAP,
                null,
                null,
                0, 0xFFFFFF,
                false, false,
                0, 100,
                false, false,
                180.0, 180.0,
                src,
                false
        );

        Bitmap rendered = new FrameSnapshot(
                1, 2, 1, 0,
                List.of(sprite),
                "",
                null,
                0,
                RenderPipelineTrace.EMPTY
        ).renderFrame();

        assertEquals(0xFF0000FF, rendered.getPixel(0, 0));
        assertEquals(0xFFFF0000, rendered.getPixel(1, 0));
    }

    @Test
    void directorMirrorAndFlipHCancelOut() {
        Bitmap src = new Bitmap(2, 1, 32, new int[]{
                0xFFFF0000,
                0xFF0000FF
        });
        RenderSprite sprite = new RenderSprite(
                1,
                0, 0,
                2, 1,
                0,
                true,
                RenderSprite.SpriteType.BITMAP,
                null,
                null,
                0, 0xFFFFFF,
                false, false,
                0, 100,
                true, false,
                180.0, 180.0,
                src,
                false
        );

        Bitmap rendered = new FrameSnapshot(
                1, 2, 1, 0,
                List.of(sprite),
                "",
                null,
                0,
                RenderPipelineTrace.EMPTY
        ).renderFrame();

        assertEquals(0xFFFF0000, rendered.getPixel(0, 0));
        assertEquals(0xFF0000FF, rendered.getPixel(1, 0));
    }

    @Test
    void skewedSpriteRendersAsParallelogram() {
        Bitmap src = new Bitmap(2, 2, 32, new int[]{
                0xFFFF0000, 0xFFFF0000,
                0xFFFF0000, 0xFFFF0000
        });
        RenderSprite sprite = new RenderSprite(
                1,
                1, 0,
                2, 2,
                0,
                true,
                RenderSprite.SpriteType.BITMAP,
                null,
                null,
                0, 0xFFFFFF,
                false, false,
                0, 100,
                false, false,
                0.0, 45.0,
                src,
                false
        );

        Bitmap rendered = new FrameSnapshot(
                1, 5, 4, 0x112233,
                List.of(sprite),
                "",
                null,
                0,
                RenderPipelineTrace.EMPTY
        ).renderFrame();

        assertEquals(0xFFFF0000, rendered.getPixel(2, 0));
        assertEquals(0xFF112233, rendered.getPixel(3, 0),
                "Skewed sprites must not fill the whole bounding box");
        assertEquals(0xFFFF0000, rendered.getPixel(3, 1),
                "The lower edge should be shifted by the skew transform");
        assertEquals(0xFF112233, rendered.getPixel(1, 1),
                "Skewed sprites must not fill the whole bounding box");
    }

    @Test
    void skewedSpriteKeepsTransparentPixelsTransparent() {
        Bitmap src = new Bitmap(2, 2, 32, new int[]{
                0x00FFFFFF, 0xFFFF0000,
                0x00FFFFFF, 0xFFFF0000
        });
        RenderSprite sprite = new RenderSprite(
                1,
                1, 0,
                2, 2,
                0,
                true,
                RenderSprite.SpriteType.BITMAP,
                null,
                null,
                0, 0xFFFFFF,
                false, false,
                0, 100,
                false, false,
                0.0, 45.0,
                src,
                false
        );

        Bitmap rendered = new FrameSnapshot(
                1, 5, 4, 0x112233,
                List.of(sprite),
                "",
                null,
                0,
                RenderPipelineTrace.EMPTY
        ).renderFrame();

        assertEquals(0xFF112233, rendered.getPixel(1, 0));
        assertEquals(0xFFFF0000, rendered.getPixel(2, 0));
        assertEquals(0xFFFF0000, rendered.getPixel(3, 1));
    }

    @Test
    void skewUsesRegistrationPointAsTransformPivot() {
        Bitmap src = new Bitmap(4, 4, 32);
        src.fill(0xFFFF0000);
        RenderSprite sprite = new RenderSprite(
                1,
                1, 1,
                4, 4,
                0,
                true,
                RenderSprite.SpriteType.BITMAP,
                null,
                null,
                0, 0xFFFFFF,
                null, null,
                false, false,
                0, 100,
                false, false,
                0.0, 45.0,
                2, 2,
                src,
                false
        );

        Bitmap rendered = new FrameSnapshot(
                1, 6, 7, 0x112233,
                List.of(sprite),
                "",
                null,
                0,
                RenderPipelineTrace.EMPTY
        ).renderFrame();

        assertEquals(0xFFFF0000, rendered.getPixel(0, 1),
                "Director skews around the sprite registration point, shifting the top edge left");
        assertEquals(0xFF112233, rendered.getPixel(4, 1),
                "Skew must not be anchored at the untransformed top-left corner");
    }

    @Test
    void addPinBlackPixelsDoNotDarkenTheStage() {
        Bitmap src = new Bitmap(2, 1, 32, new int[]{
                0xFF000000,
                0xFF202020
        });
        RenderSprite sprite = new RenderSprite(
                1,
                0, 0,
                2, 1,
                0,
                true,
                RenderSprite.SpriteType.BITMAP,
                null,
                null,
                0, 0xFFFFFF,
                false, false,
                33, 100,
                false, false,
                0.0, 0.0,
                src,
                false
        );

        Bitmap rendered = new FrameSnapshot(
                1, 2, 1, 0x406080,
                List.of(sprite),
                "",
                null,
                0,
                RenderPipelineTrace.EMPTY
        ).renderFrame();

        assertEquals(0xFF406080, rendered.getPixel(0, 0));
        assertEquals(0xFF6080A0, rendered.getPixel(1, 0));
    }

    @Test
    void scaledAddPinBlackPixelsDoNotDarkenTheStage() {
        Bitmap src = new Bitmap(2, 1, 32, new int[]{
                0xFF000000,
                0xFF202020
        });
        RenderSprite sprite = new RenderSprite(
                1,
                0, 0,
                4, 1,
                0,
                true,
                RenderSprite.SpriteType.BITMAP,
                null,
                null,
                0, 0xFFFFFF,
                false, false,
                33, 100,
                false, false,
                0.0, 0.0,
                src,
                false
        );

        Bitmap rendered = new FrameSnapshot(
                1, 4, 1, 0x406080,
                List.of(sprite),
                "",
                null,
                0,
                RenderPipelineTrace.EMPTY
        ).renderFrame();

        assertEquals(0xFF406080, rendered.getPixel(0, 0));
        assertEquals(0xFF406080, rendered.getPixel(1, 0));
        assertEquals(0xFF6080A0, rendered.getPixel(2, 0));
        assertEquals(0xFF6080A0, rendered.getPixel(3, 0));
    }

    @Test
    void subtractPinWhitePixelCanDarkenFullscreenDimmer() {
        Bitmap src = new Bitmap(1, 1, 32, new int[]{
                0xFFFFFFFF
        });
        RenderSprite sprite = new RenderSprite(
                1,
                0, 0,
                2, 1,
                0,
                true,
                RenderSprite.SpriteType.BITMAP,
                null,
                null,
                0, 0xFFFFFF,
                false, false,
                35, 100,
                false, false,
                0.0, 0.0,
                src,
                false
        );

        Bitmap rendered = new FrameSnapshot(
                1, 2, 1, 0x406080,
                List.of(sprite),
                "",
                null,
                0,
                RenderPipelineTrace.EMPTY
        ).renderFrame();

        assertEquals(0xFF000000, rendered.getPixel(0, 0));
        assertEquals(0xFF000000, rendered.getPixel(1, 0));
    }

    @Test
    void nonPinAddAndSubtractWrapWhilePinModesClamp() {
        assertSinglePixelInk(34, 0xF0F000, 0xFF202020, 0xFF101020);
        assertSinglePixelInk(33, 0xF0F000, 0xFF202020, 0xFFFFFF20);
        assertSinglePixelInk(38, 0x103020, 0xFF204010, 0xFFF0F010);
        assertSinglePixelInk(35, 0x103020, 0xFF204010, 0xFF000010);
    }

    private static void assertSinglePixelInk(int ink, int backgroundColor, int sourceColor, int expectedColor) {
        Bitmap src = new Bitmap(1, 1, 32, new int[]{
                sourceColor
        });
        RenderSprite sprite = new RenderSprite(
                1,
                0, 0,
                1, 1,
                0,
                true,
                RenderSprite.SpriteType.BITMAP,
                null,
                null,
                0, 0xFFFFFF,
                false, false,
                ink, 100,
                false, false,
                0.0, 0.0,
                src,
                false
        );

        Bitmap rendered = new FrameSnapshot(
                1, 1, 1, backgroundColor,
                List.of(sprite),
                "",
                null,
                0,
                RenderPipelineTrace.EMPTY
        ).renderFrame();

        assertEquals(expectedColor, rendered.getPixel(0, 0));
    }
}
