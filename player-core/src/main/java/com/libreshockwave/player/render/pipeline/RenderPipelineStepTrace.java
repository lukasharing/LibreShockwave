package com.libreshockwave.player.render.pipeline;

/**
 * Immutable trace entry describing one render pipeline step.
 */
public record RenderPipelineStepTrace(
        String stepName,
        String summary,
        int spriteCount
) {
}
