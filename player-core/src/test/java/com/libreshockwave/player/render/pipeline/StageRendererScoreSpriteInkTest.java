package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.id.ChannelId;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.id.FrameIndex;
import com.libreshockwave.id.InkMode;
import com.libreshockwave.player.render.SpriteRegistry;
import com.libreshockwave.player.sprite.SpriteState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StageRendererScoreSpriteInkTest {

    @Test
    void scoreSpriteUsesRuntimeInkOverride() throws Exception {
        StageRenderer renderer = new StageRenderer(newEmptyDirectorFile());
        SpriteRegistry registry = renderer.getSpriteRegistry();
        ScoreChunk.ChannelData data = channelData(InkMode.COPY.code(), 0, 0, 0);

        registry.getOrCreate(7, data).setInk(InkMode.BLEND.code());

        RenderSprite sprite = invokeCreateRenderSprite(renderer, 7, data);
        assertNotNull(sprite);
        assertEquals(InkMode.BLEND.code(), sprite.getInk());
    }

    @Test
    void scoreUpdatesRefreshNonOverriddenInkAndBlend() {
        SpriteRegistry registry = new SpriteRegistry();
        ScoreChunk.ChannelData start = channelData(InkMode.COPY.code(), 0, 0, 0);
        ScoreChunk.ChannelData updated = channelData(InkMode.BLEND.code(), 0, 0, 255);

        registry.getOrCreate(3, start);
        registry.updateFromScore(3, updated);

        assertEquals(InkMode.BLEND.code(), registry.get(3).getInk());
        assertEquals(0, registry.get(3).getBlend());
    }

    @Test
    void scoreUpdatesPreserveExplicitInkAndBlendOverrides() {
        SpriteRegistry registry = new SpriteRegistry();
        ScoreChunk.ChannelData start = channelData(InkMode.COPY.code(), 0, 0, 0);
        ScoreChunk.ChannelData updated = channelData(InkMode.BLEND.code(), 0, 0, 255);

        var state = registry.getOrCreate(4, start);
        state.setInk(InkMode.DARKEN.code());
        state.setBlend(77);

        registry.updateFromScore(4, updated);

        assertEquals(InkMode.DARKEN.code(), registry.get(4).getInk());
        assertEquals(77, registry.get(4).getBlend());
    }

    @Test
    void frameEntrySyncCreatesAndUpdatesScoreSpriteState() throws Exception {
        DirectorFile file = newEmptyDirectorFile();
        installScore(file, List.of(
                frameEntry(0, 8, channelDataAt(10, 20)),
                frameEntry(1, 8, channelDataAt(30, 40))
        ));
        StageRenderer renderer = new StageRenderer(file);

        renderer.syncScoreStateForFrame(1);

        SpriteState state = renderer.getSpriteRegistry().get(8);
        assertNotNull(state);
        assertEquals(10, state.getLocH());
        assertEquals(20, state.getLocV());

        renderer.syncScoreStateForFrame(2);

        assertEquals(30, state.getLocH());
        assertEquals(40, state.getLocV());
    }

    @Test
    void frameEntrySyncDoesNotResetPuppetedSpritePosition() throws Exception {
        DirectorFile file = newEmptyDirectorFile();
        installScore(file, List.of(
                frameEntry(0, 8, channelDataAt(10, 20)),
                frameEntry(1, 8, channelDataAt(30, 40))
        ));
        StageRenderer renderer = new StageRenderer(file);

        renderer.syncScoreStateForFrame(1);
        SpriteState state = renderer.getSpriteRegistry().get(8);
        assertNotNull(state);
        state.setPuppet(true);
        state.setLocH(111);
        state.setLocV(222);
        state.setLocZ(333);

        renderer.syncScoreStateForFrame(2);

        assertEquals(111, state.getLocH());
        assertEquals(222, state.getLocV());
        assertEquals(333, state.getLocZ());
    }

    @Test
    void explicitEmptyDynamicMemberDoesNotRenderAsColorOnlyPuppetSprite() throws Exception {
        StageRenderer renderer = new StageRenderer(null);
        SpriteState state = renderer.getSpriteRegistry().getOrCreateDynamic(12);
        state.setVisible(true);
        state.setPuppet(true);
        state.setBackColor(0xFF00FF);
        state.setWidth(20);
        state.setHeight(10);
        state.setDynamicMember(0, 0);

        assertNull(invokeCreateDynamicRenderSprite(renderer, state));
    }

    private static RenderSprite invokeCreateRenderSprite(StageRenderer renderer, int channel,
                                                         ScoreChunk.ChannelData data) throws Exception {
        Method method = StageRenderer.class.getDeclaredMethod(
            "createRenderSprite", int.class, ScoreChunk.ChannelData.class);
        method.setAccessible(true);
        return (RenderSprite) method.invoke(renderer, channel, data);
    }

    private static RenderSprite invokeCreateDynamicRenderSprite(StageRenderer renderer, SpriteState state)
            throws Exception {
        Method method = StageRenderer.class.getDeclaredMethod(
            "createDynamicRenderSprite", SpriteState.class);
        method.setAccessible(true);
        return (RenderSprite) method.invoke(renderer, state);
    }

    private static DirectorFile newEmptyDirectorFile() throws Exception {
        Constructor<DirectorFile> ctor = DirectorFile.class.getDeclaredConstructor(
            ByteOrder.class, boolean.class, int.class, ChunkType.class);
        ctor.setAccessible(true);
        return ctor.newInstance(ByteOrder.BIG_ENDIAN, false, 0, ChunkType.RIFX);
    }

    private static void installScore(DirectorFile file, List<ScoreChunk.FrameChannelEntry> entries) throws Exception {
        ScoreChunk.ScoreFrameData frameData = new ScoreChunk.ScoreFrameData(
                new ScoreChunk.FrameDataHeader(2, 28, 10, 0),
                new byte[0],
                entries,
                List.of(),
                List.of()
        );
        ScoreChunk score = new ScoreChunk(
                file,
                new ChunkId(1),
                new ScoreChunk.Header(0, 0, 0, 0, 0, 0),
                List.of(),
                frameData,
                List.of()
        );
        Field field = DirectorFile.class.getDeclaredField("scoreChunk");
        field.setAccessible(true);
        field.set(file, score);
    }

    private static ScoreChunk.FrameChannelEntry frameEntry(int frameIndex, int channel,
                                                           ScoreChunk.ChannelData data) {
        return new ScoreChunk.FrameChannelEntry(
                new FrameIndex(frameIndex),
                new ChannelId(channel),
                data
        );
    }

    private static ScoreChunk.ChannelData channelDataAt(int locH, int locV) {
        return new ScoreChunk.ChannelData(
                1,
                InkMode.COPY.code(),
                0,
                0,
                0,
                0,
                1,
                1,
                0,
                0,
                locV,
                locH,
                10,
                10,
                0,
                0,
                0,
                0,
                0,
                0,
                0
        );
    }

    private static ScoreChunk.ChannelData channelData(int ink, int trails, int stretch, int blendByte) {
        return new ScoreChunk.ChannelData(
            1,
            ink,
            trails,
            stretch,
            0,
            0,
            0,
            0,
            0,
            0,
            10,
            10,
            10,
            10,
            0,
            blendByte,
            0,
            0,
            0,
            0,
            0
        );
    }

}
