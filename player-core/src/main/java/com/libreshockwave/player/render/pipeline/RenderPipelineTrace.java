package com.libreshockwave.player.render.pipeline;

import java.util.List;

/**
 * Ordered trace of the frame render pipeline.
 */
public record RenderPipelineTrace(List<RenderPipelineStepTrace> steps) {

    public static final RenderPipelineTrace EMPTY = new RenderPipelineTrace(List.of());

    public RenderPipelineTrace {
        steps = List.copyOf(steps);
    }
}
