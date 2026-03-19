package com.libreshockwave.player.render.pipeline;

/**
 * A single polymorphic step in the frame render pipeline.
 */
public interface FrameRenderPipelineStep {

    String name();

    void execute(FrameRenderPipelineContext context);
}
