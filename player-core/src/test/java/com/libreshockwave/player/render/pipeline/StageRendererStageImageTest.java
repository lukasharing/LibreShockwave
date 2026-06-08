package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.bitmap.Bitmap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StageRendererStageImageTest {

    @Test
    void pristineStageImageDoesNotOverrideUpdatedBackground() {
        StageRenderer renderer = new StageRenderer(null);

        Bitmap stageImage = renderer.getStageImage();
        assertEquals(0xFFFFFFFF, stageImage.getPixel(0, 0));

        renderer.setBackgroundColor(0x000000);

        assertEquals(0xFF000000, stageImage.getPixel(0, 0));
        assertNull(renderer.getRenderableStageImage());

        FrameSnapshot snapshot = new FrameSnapshot(
                1,
                1,
                1,
                renderer.getBackgroundColor(),
                List.of(),
                "",
                renderer.getRenderableStageImage(),
                0,
                RenderPipelineTrace.EMPTY
        );

        assertEquals(0xFF000000, snapshot.renderFrame().getPixel(0, 0));
    }

    @Test
    void scriptModifiedStageImageRendersForOnePublishedSnapshot() {
        StageRenderer renderer = new StageRenderer(null);

        Bitmap stageImage = renderer.getStageImage();
        stageImage.setPixel(0, 0, 0xFFFFFFFF);
        stageImage.markScriptModified();
        renderer.setBackgroundColor(0x000000);

        assertSame(stageImage, renderer.getRenderableStageImage());

        FrameSnapshot snapshot = renderWithPipeline(renderer);

        assertEquals(0xFFFFFFFF, snapshot.renderFrame().getPixel(0, 0));
        assertFalse(renderer.hasStageImage());

        FrameSnapshot nextSnapshot = renderWithPipeline(renderer);

        assertNull(nextSnapshot.stageImage());
        assertEquals(0xFF000000, nextSnapshot.renderFrame().getPixel(0, 0));
    }

    @Test
    void stageImageLifecycleInvalidatesVisualRevision() {
        StageRenderer renderer = new StageRenderer(null);

        int initialRevision = renderer.getSpriteRegistry().getRevision();
        Bitmap stageImage = renderer.getStageImage();
        int createdRevision = renderer.getSpriteRegistry().getRevision();

        assertTrue(createdRevision > initialRevision);

        stageImage.setPixel(0, 0, 0xFFFFFFFF);
        stageImage.markScriptModified();
        int mutatedRevision = renderer.getSpriteRegistry().getRevision();

        assertTrue(mutatedRevision > createdRevision);

        renderWithPipeline(renderer);

        assertFalse(renderer.hasStageImage());
        assertTrue(renderer.getSpriteRegistry().getRevision() > mutatedRevision);
    }

    @Test
    void consumedStageImageReferenceDoesNotBecomeRenderableAgain() {
        StageRenderer renderer = new StageRenderer(null);
        Bitmap stageImage = renderer.getStageImage();
        stageImage.setPixel(0, 0, 0xFFFFFFFF);
        stageImage.markScriptModified();

        renderWithPipeline(renderer);

        stageImage.setPixel(0, 0, 0xFFFFFF00);
        stageImage.markScriptModified();

        assertNull(renderer.getRenderableStageImage());
        assertFalse(renderer.hasStageImage());
    }

    @Test
    void resetClearsStageImage() {
        StageRenderer renderer = new StageRenderer(null);
        renderer.getStageImage().markScriptModified();

        renderer.reset();

        assertFalse(renderer.hasStageImage());
        assertNull(renderer.getRenderableStageImage());
    }

    @Test
    void discardStageImageDropsScriptModifiedBuffer() {
        StageRenderer renderer = new StageRenderer(null);
        Bitmap stageImage = renderer.getStageImage();
        stageImage.setPixel(0, 0, 0xFFFFFF00);
        stageImage.markScriptModified();

        renderer.discardStageImage();

        assertFalse(renderer.hasStageImage());
        assertNull(renderer.getRenderableStageImage());
        assertEquals(0xFFFFFFFF, renderer.getStageImage().getPixel(0, 0));
    }

    @Test
    void resetVisualStateRestoresDefaultBackground() {
        StageRenderer renderer = new StageRenderer(null);
        renderer.setDefaultBackgroundColor(0x000000);
        Bitmap stageImage = renderer.getStageImage();
        stageImage.setPixel(0, 0, 0xFF7F7F00);
        stageImage.markScriptModified();
        renderer.setBackgroundColor(0x808000);

        renderer.resetVisualState();

        assertEquals(0x000000, renderer.getBackgroundColor());
        assertNull(renderer.getRenderableStageImage());
        assertEquals(0xFF000000, renderer.getStageImage().getPixel(0, 0));
    }

    private static FrameSnapshot renderWithPipeline(StageRenderer renderer) {
        return new FrameRenderPipeline(renderer, new SpriteBaker(new BitmapCache(), null, null))
                .renderFrame(1);
    }
}
