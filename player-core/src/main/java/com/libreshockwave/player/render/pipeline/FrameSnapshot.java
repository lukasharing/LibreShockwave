package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.render.output.SoftwareFrameRenderer;

import java.util.List;

/**
 * Complete snapshot of what to render for a single frame.
 * Immutable data structure for thread-safe rendering.
 */
public record FrameSnapshot(
    int frameNumber,
    int stageWidth,
    int stageHeight,
    int backgroundColor,
    List<RenderSprite> sprites,
    String debugInfo,
    Bitmap stageImage,
    int bakeTick,
    RenderPipelineTrace pipelineTrace
) {
    /**
     * Render this snapshot to a Bitmap using pure software compositing.
     * No AWT dependency — works on all platforms including WASM.
     */
    public Bitmap renderFrame() {
        return SoftwareFrameRenderer.renderFrame(this, stageWidth, stageHeight);
    }
}
