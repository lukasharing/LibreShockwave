package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.bitmap.Bitmap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mutable per-frame context passed through the render pipeline.
 */
public final class FrameRenderPipelineContext {

    private final int frameNumber;
    private final int stageWidth;
    private final int stageHeight;
    private final int backgroundColor;
    private final Bitmap stageImage;
    private final String debugInfo;

    private final List<RenderSprite> sprites = new ArrayList<>();
    private final Set<Integer> renderedChannels = new HashSet<>();
    private final List<RenderPipelineStepTrace> trace = new ArrayList<>();

    private FrameSnapshot snapshot;

    public FrameRenderPipelineContext(int frameNumber,
                                      int stageWidth,
                                      int stageHeight,
                                      int backgroundColor,
                                      Bitmap stageImage,
                                      String debugInfo) {
        this.frameNumber = frameNumber;
        this.stageWidth = stageWidth;
        this.stageHeight = stageHeight;
        this.backgroundColor = backgroundColor;
        this.stageImage = stageImage;
        this.debugInfo = debugInfo;
    }

    public int frameNumber() {
        return frameNumber;
    }

    public int stageWidth() {
        return stageWidth;
    }

    public int stageHeight() {
        return stageHeight;
    }

    public int backgroundColor() {
        return backgroundColor;
    }

    public Bitmap stageImage() {
        return stageImage;
    }

    public String debugInfo() {
        return debugInfo;
    }

    public List<RenderSprite> sprites() {
        return sprites;
    }

    public Set<Integer> renderedChannels() {
        return renderedChannels;
    }

    public void addTrace(String stepName, String summary) {
        trace.add(new RenderPipelineStepTrace(stepName, summary, sprites.size()));
    }

    public RenderPipelineTrace buildTrace() {
        return trace.isEmpty() ? RenderPipelineTrace.EMPTY : new RenderPipelineTrace(trace);
    }

    public FrameSnapshot snapshot() {
        return snapshot;
    }

    public void setSnapshot(FrameSnapshot snapshot) {
        this.snapshot = snapshot;
    }
}
