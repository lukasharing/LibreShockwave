package com.libreshockwave.player.render;

import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

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
    void castRetirementPreservesDynamicSpriteBindingsFromThatCast() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteState stale = registry.getOrCreateDynamic(12);
        stale.setBackColor(0xFF00FF);
        stale.setDynamicMember(7, 10001);
        stale.setScriptInstanceList(java.util.List.of(Datum.of(1)));

        SpriteState other = registry.getOrCreateDynamic(13);
        other.setDynamicMember(8, 10001);

        assertTrue(registry.clearDynamicMemberBindingsForCast(7));

        assertTrue(stale.isVisible());
        assertTrue(stale.hasDynamicMember());
        assertEquals(7, stale.getEffectiveCastLib());
        assertEquals(10001, stale.getEffectiveCastMember());
        assertTrue(stale.getScriptInstanceList().isEmpty());
        assertEquals(2, registry.getDynamicSprites().size());
    }

    @Test
    void castRetirementKeepsScoreBackedRuntimeBindingIdentity() {
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

        assertTrue(sprite.isVisible());
        assertTrue(sprite.hasDynamicMember());
        assertEquals(7, sprite.getEffectiveCastLib());
        assertEquals(10001, sprite.getEffectiveCastMember());
        assertTrue(sprite.getScriptInstanceList().isEmpty());
        assertEquals(1, registry.getDynamicSprites().size());
    }

    @Test
    void runtimeMemberRetirementKeepsScoreBackedBindingIdentity() {
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

        assertTrue(sprite.isVisible());
        assertTrue(sprite.hasDynamicMember());
        assertEquals(7, sprite.getEffectiveCastLib());
        assertEquals(10001, sprite.getEffectiveCastMember());
        assertTrue(sprite.getScriptInstanceList().isEmpty());
        assertEquals(1, registry.getDynamicSprites().size());
    }

    @Test
    void runtimeMemberRetirementPreservesDynamicBindingIdentity() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteState sprite = registry.getOrCreateDynamic(12);
        sprite.setBackColor(0xFF00FF);
        sprite.setDynamicMember(7, 10001);
        sprite.setScriptInstanceList(java.util.List.of(Datum.of(1)));

        assertTrue(registry.clearDynamicMemberBindings(7, 10001));

        assertTrue(sprite.isVisible());
        assertTrue(sprite.hasDynamicMember());
        assertEquals(7, sprite.getEffectiveCastLib());
        assertEquals(10001, sprite.getEffectiveCastMember());
        assertTrue(sprite.getScriptInstanceList().isEmpty());
        assertEquals(1, registry.getDynamicSprites().size());
    }

    @Test
    void retiredDynamicBindingKeepsEmptyOverrideAcrossLateScoreReuse() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteState sprite = registry.getOrCreateDynamic(12);
        sprite.setDynamicMember(7, 10001);

        assertTrue(registry.clearDynamicMemberBindings(7, 10001));

        registry.getOrCreate(12, new ScoreChunk.ChannelData(
                1, 0, 0, 0, 0, 0,
                3, 40,
                0, 0, 10, 20, 30, 40,
                0, 0, 0, 0, 0, 0, 0
        ));

        assertTrue(sprite.hasDynamicMember(),
                "retired runtime channels keep their cast/member identity until authored Lingo assigns a new member");
        assertEquals(7, sprite.getEffectiveCastLib());
        assertEquals(10001, sprite.getEffectiveCastMember());
        assertTrue(sprite.isVisible());
        assertEquals(1, registry.getDynamicSprites().size());
    }

    @Test
    void removingRegisteredSpriteInvalidatesRenderRevision() {
        SpriteRegistry registry = new SpriteRegistry();
        AtomicInteger notifications = new AtomicInteger();
        registry.setRevisionListener(notifications::incrementAndGet);
        registry.getOrCreateDynamic(12);

        int before = registry.getRevision();
        registry.remove(12);

        assertTrue(registry.getRevision() > before);
        assertEquals(1, notifications.get());
        assertFalse(registry.contains(12));
    }

    @Test
    void removingMissingSpriteDoesNotInvalidateRenderRevision() {
        SpriteRegistry registry = new SpriteRegistry();
        AtomicInteger notifications = new AtomicInteger();
        registry.setRevisionListener(notifications::incrementAndGet);

        registry.remove(12);

        assertEquals(0, registry.getRevision());
        assertEquals(0, notifications.get());
    }

    @Test
    void clearingRegisteredSpritesInvalidatesRenderRevision() {
        SpriteRegistry registry = new SpriteRegistry();
        AtomicInteger notifications = new AtomicInteger();
        registry.setRevisionListener(notifications::incrementAndGet);
        registry.getOrCreateDynamic(12);
        registry.getOrCreateDynamic(13);

        int before = registry.getRevision();
        registry.clear();

        assertTrue(registry.getRevision() > before);
        assertEquals(1, notifications.get());
        assertTrue(registry.getAll().isEmpty());
    }

    @Test
    void clearingEmptyRegistryDoesNotInvalidateRenderRevision() {
        SpriteRegistry registry = new SpriteRegistry();
        AtomicInteger notifications = new AtomicInteger();
        registry.setRevisionListener(notifications::incrementAndGet);

        registry.clear();

        assertEquals(0, registry.getRevision());
        assertEquals(0, notifications.get());
    }
}
