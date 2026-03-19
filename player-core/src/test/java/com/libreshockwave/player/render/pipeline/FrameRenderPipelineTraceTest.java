package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameRenderPipelineTraceTest {

    @Test
    void pipelineProducesOrderedTraceForFrame() throws Exception {
        StageRenderer renderer = new StageRenderer(newEmptyDirectorFile());
        SpriteBaker baker = new SpriteBaker(new BitmapCache(), null, null);
        FrameRenderPipeline pipeline = new FrameRenderPipeline(renderer, baker);

        FrameSnapshot snapshot = pipeline.renderFrame(1);

        List<String> stepNames = snapshot.pipelineTrace().steps().stream()
                .map(RenderPipelineStepTrace::stepName)
                .toList();

        assertEquals(List.of(
                "collect-score-sprites",
                "collect-dynamic-sprites",
                "order-sprites",
                "bake-sprites",
                "publish-baked-sprites",
                "build-frame-snapshot"
        ), stepNames);
    }

    private static DirectorFile newEmptyDirectorFile() throws Exception {
        Constructor<DirectorFile> ctor = DirectorFile.class.getDeclaredConstructor(
                ByteOrder.class, boolean.class, int.class, ChunkType.class);
        ctor.setAccessible(true);
        return ctor.newInstance(ByteOrder.BIG_ENDIAN, false, 0, ChunkType.RIFX);
    }
}
