package com.libreshockwave.player.render.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit, step-driven frame render pipeline.
 * The pipeline is intentionally linear so each stage can be inspected.
 */
public final class FrameRenderPipeline {

    private static String lastStage = "";

    private final StageRenderer stageRenderer;
    private final SpriteBaker spriteBaker;
    private final List<FrameRenderPipelineStep> steps = new ArrayList<>();

    public FrameRenderPipeline(StageRenderer stageRenderer, SpriteBaker spriteBaker) {
        this.stageRenderer = stageRenderer;
        this.spriteBaker = spriteBaker;
        registerDefaultSteps();
    }

    private void registerDefaultSteps() {
        registerStep(new CollectScoreSpritesStep());
        registerStep(new CollectDynamicSpritesStep());
        registerStep(new OrderSpritesStep());
        registerStep(new BakeSpritesStep());
        registerStep(new PublishBakedSpritesStep());
        registerStep(new BuildSnapshotStep());
    }

    public void registerStep(FrameRenderPipelineStep step) {
        steps.add(step);
    }

    public static String getLastStage() {
        return lastStage;
    }

    public FrameSnapshot renderFrame(int frameNumber) {
        lastStage = "init";
        var renderableStageImage = stageRenderer.getRenderableStageImage();
        FrameRenderPipelineContext context = new FrameRenderPipelineContext(
                frameNumber,
                stageRenderer.getStageWidth(),
                stageRenderer.getStageHeight(),
                stageRenderer.getBackgroundColor(),
                renderableStageImage,
                stageRenderer.getRenderableStageImageRevision(),
                "Frame " + frameNumber
        );

        for (FrameRenderPipelineStep step : steps) {
            lastStage = step.name();
            step.execute(context);
        }

        lastStage = "verify-snapshot";
        if (context.snapshot() == null) {
            throw new IllegalStateException("Frame render pipeline did not produce a snapshot");
        }

        lastStage = "done";
        return context.snapshot();
    }

    private final class CollectScoreSpritesStep implements FrameRenderPipelineStep {
        @Override
        public String name() {
            return "collect-score-sprites";
        }

        @Override
        public void execute(FrameRenderPipelineContext context) {
            int before = context.sprites().size();
            stageRenderer.collectScoreSprites(context.frameNumber(), context.sprites(), context.renderedChannels());
            int added = context.sprites().size() - before;
            context.addTrace(name(), "Collected " + added + " score sprites");
        }
    }

    private final class CollectDynamicSpritesStep implements FrameRenderPipelineStep {
        @Override
        public String name() {
            return "collect-dynamic-sprites";
        }

        @Override
        public void execute(FrameRenderPipelineContext context) {
            int before = context.sprites().size();
            stageRenderer.collectDynamicSprites(context.sprites(), context.renderedChannels());
            int added = context.sprites().size() - before;
            context.addTrace(name(), "Collected " + added + " dynamic sprites");
        }
    }

    private final class OrderSpritesStep implements FrameRenderPipelineStep {
        @Override
        public String name() {
            return "order-sprites";
        }

        @Override
        public void execute(FrameRenderPipelineContext context) {
            stageRenderer.sortSprites(context.sprites());
            context.addTrace(name(), "Ordered sprites by locZ then channel");
        }
    }

    private final class BakeSpritesStep implements FrameRenderPipelineStep {
        @Override
        public String name() {
            return "bake-sprites";
        }

        @Override
        public void execute(FrameRenderPipelineContext context) {
            List<RenderSprite> baked = spriteBaker.bakeSprites(context.sprites());
            context.sprites().clear();
            context.sprites().addAll(baked);
            context.addTrace(name(), "Baked sprites into renderable bitmaps");
        }
    }

    private final class PublishBakedSpritesStep implements FrameRenderPipelineStep {
        @Override
        public String name() {
            return "publish-baked-sprites";
        }

            @Override
        public void execute(FrameRenderPipelineContext context) {
            stageRenderer.setLastBakedSprites(new ArrayList<>(context.sprites()));
            context.addTrace(name(), "Published baked sprites for hit testing");
        }
    }

    private final class BuildSnapshotStep implements FrameRenderPipelineStep {
        @Override
        public String name() {
            return "build-frame-snapshot";
        }

        @Override
        public void execute(FrameRenderPipelineContext context) {
            context.addTrace(name(), "Built immutable frame snapshot");
            context.setSnapshot(new FrameSnapshot(
                    context.frameNumber(),
                    context.stageWidth(),
                    context.stageHeight(),
                    context.backgroundColor(),
                    new ArrayList<>(context.sprites()),
                    context.debugInfo(),
                    context.stageImage(),
                    spriteBaker.getRenderRevision(),
                    context.buildTrace()
            ));
            stageRenderer.consumeRenderableStageImage(context.stageImage(), context.stageImageRevision());
        }
    }
}
