package com.libreshockwave.player.render;

import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpriteRegistryTest {

    @Test
    void emptyDynamicSpriteStateIsNotReturnedForRendering() {
        SpriteRegistry registry = new SpriteRegistry();

        registry.getOrCreateDynamic(12);

        assertTrue(registry.getDynamicSprites().isEmpty());
    }

    @Test
    void dynamicMemberSpriteIsReturnedForRendering() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteState sprite = registry.getOrCreateDynamic(12);
        sprite.setDynamicMember(1, 2);

        assertEquals(1, registry.getDynamicSprites().size());
    }

    @Test
    void explicitEmptyDynamicMemberSpriteIsNotReturnedForRendering() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteState sprite = registry.getOrCreateDynamic(12);
        sprite.setBackColor(0xFF00FF);
        sprite.setDynamicMember(0, 0);

        assertTrue(registry.getDynamicSprites().isEmpty());
    }

    @Test
    void colorOnlyPuppetSpriteIsReturnedForRendering() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteState sprite = registry.getOrCreateDynamic(12);
        sprite.setBackColor(0xFF00FF);

        assertEquals(1, registry.getDynamicSprites().size());
    }

    @Test
    void castRetirementHidesDynamicSpriteBindingsFromThatCast() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteState stale = registry.getOrCreateDynamic(12);
        stale.setBackColor(0xFF00FF);
        stale.setDynamicMember(7, 10001);
        stale.setScriptInstanceList(java.util.List.of(Datum.of(1)));

        SpriteState other = registry.getOrCreateDynamic(13);
        other.setDynamicMember(8, 10001);

        assertTrue(registry.clearDynamicMemberBindingsForCast(7));

        assertFalse(stale.isVisible());
        assertFalse(stale.hasDynamicMember());
        assertTrue(stale.getScriptInstanceList().isEmpty());
        assertEquals(1, registry.getDynamicSprites().size());
        assertEquals(13, registry.getDynamicSprites().get(0).getChannel());
    }

    @Test
    void castRetirementKeepsScoreBackedRuntimeBindingExplicitlyEmpty() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteState sprite = registry.getOrCreate(9, new ScoreChunk.ChannelData(
                1, 0, 0, 0, 0, 0,
                3, 40,
                0, 0, 10, 20, 30, 40,
                0, 0, 0, 0, 0, 0, 0
        ));
        sprite.setDynamicMember(7, 10001);
        sprite.setScriptInstanceList(java.util.List.of(Datum.of(1)));

        assertTrue(registry.clearDynamicMemberBindingsForCast(7));

        assertFalse(sprite.isVisible());
        assertTrue(sprite.hasDynamicMember());
        assertEquals(0, sprite.getEffectiveCastLib());
        assertEquals(0, sprite.getEffectiveCastMember());
        assertTrue(sprite.getScriptInstanceList().isEmpty());
        assertTrue(registry.getDynamicSprites().isEmpty());
    }

    @Test
    void runtimeMemberRetirementKeepsScoreBackedBindingExplicitlyEmpty() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteState sprite = registry.getOrCreate(9, new ScoreChunk.ChannelData(
                1, 0, 0, 0, 0, 0,
                3, 40,
                0, 0, 10, 20, 30, 40,
                0, 0, 0, 0, 0, 0, 0
        ));
        sprite.setDynamicMember(7, 10001);
        sprite.setScriptInstanceList(java.util.List.of(Datum.of(1)));

        assertTrue(registry.clearDynamicMemberBindings(7, 10001));

        assertFalse(sprite.isVisible());
        assertTrue(sprite.hasDynamicMember());
        assertEquals(0, sprite.getEffectiveCastLib());
        assertEquals(0, sprite.getEffectiveCastMember());
        assertTrue(sprite.getScriptInstanceList().isEmpty());
        assertTrue(registry.getDynamicSprites().isEmpty());
    }

    @Test
    void runtimeMemberRetirementHidesDynamicBinding() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteState sprite = registry.getOrCreateDynamic(12);
        sprite.setBackColor(0xFF00FF);
        sprite.setDynamicMember(7, 10001);
        sprite.setScriptInstanceList(java.util.List.of(Datum.of(1)));

        assertTrue(registry.clearDynamicMemberBindings(7, 10001));

        assertFalse(sprite.isVisible());
        assertFalse(sprite.hasDynamicMember());
        assertTrue(sprite.getScriptInstanceList().isEmpty());
        assertTrue(registry.getDynamicSprites().isEmpty());
    }
}
