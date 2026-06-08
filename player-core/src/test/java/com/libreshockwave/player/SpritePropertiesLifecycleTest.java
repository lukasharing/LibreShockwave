package com.libreshockwave.player;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.player.cast.CastLib;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.render.SpriteRegistry;
import com.libreshockwave.player.sprite.SpriteColorSource;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpritePropertiesLifecycleTest {

    @Test
    void memberZeroCreatesExplicitEmptyOverride() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        assertTrue(props.setSpriteProp(17, "member", Datum.CastMemberRef.of(3, 42)));

        SpriteState state = registry.get(17);
        assertTrue(state.hasDynamicMember());
        assertEquals(3, state.getEffectiveCastLib());
        assertEquals(42, state.getEffectiveCastMember());

        assertTrue(props.setSpriteProp(17, "member", Datum.CastMemberRef.of(1, 0)));

        assertTrue(state.hasDynamicMember());
        assertEquals(0, state.getEffectiveCastLib());
        assertEquals(0, state.getEffectiveCastMember());
    }

    @Test
    void mirroredMemberRefMarksSpriteAsMirrored() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        assertTrue(props.setSpriteProp(17, "member", Datum.CastMemberRef.of(3, 42, true)));

        SpriteState state = registry.get(17);
        assertTrue(state.hasDynamicMember());
        assertEquals(3, state.getEffectiveCastLib());
        assertEquals(42, state.getEffectiveCastMember());
        assertTrue(state.isEffectiveMemberMirrored());

        Datum member = props.getSpriteProp(17, "member");
        assertTrue(member instanceof Datum.CastMemberRef);
        assertTrue(((Datum.CastMemberRef) member).isMirrored());
    }

    @Test
    void negativeEncodedMemberNumMarksSpriteAsMirrored() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        assertTrue(props.setSpriteProp(17, "memberNum", Datum.of(-((11 << 16) | 7))));

        SpriteState state = registry.get(17);
        assertEquals(11, state.getEffectiveCastLib());
        assertEquals(7, state.getEffectiveCastMember());
        assertTrue(state.isEffectiveMemberMirrored());
    }

    @Test
    void colorAndBgColorAssignDirectorColorValues() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        assertTrue(props.setSpriteProp(17, "color", new Datum.Color(0x12, 0x34, 0x56)));
        assertTrue(props.setSpriteProp(17, "bgColor", new Datum.Color(0xAB, 0xCD, 0xEF)));

        SpriteState state = registry.get(17);
        assertEquals(0x123456, state.getForeColor());
        assertEquals(0xABCDEF, state.getBackColor());
        assertEquals(0x123456, props.getSpriteProp(17, "color").toInt());
        assertEquals(0xABCDEF, props.getSpriteProp(17, "bgColor").toInt());
    }

    @Test
    void paletteIndexSpriteColorsKeepRawPaletteIndex() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        assertTrue(props.setSpriteProp(17, "color", new Datum.PaletteIndexColor(17)));
        assertTrue(props.setSpriteProp(17, "bgColor", new Datum.PaletteIndexColor(42)));

        SpriteState state = registry.get(17);
        assertEquals(17, state.getForeColor());
        assertEquals(42, state.getBackColor());
    }

    @Test
    void starHexPaletteIndexSpriteColorActsAsPackedRgb() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        assertTrue(props.setSpriteProp(17, "bgColor", new Datum.PaletteIndexColor(0xFFFFFF)));

        SpriteState state = registry.get(17);
        assertEquals(0xFFFFFF, state.getBackColor());
        assertEquals(SpriteColorSource.RGB, state.getBackColorSource());
    }

    @Test
    void memberZeroResetsReleasedSpriteTransformState() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        assertTrue(props.setSpriteProp(17, "member", Datum.CastMemberRef.of(3, 42)));

        SpriteState state = registry.get(17);
        state.setFlipH(true);
        state.setFlipV(true);
        state.setRotation(180.0);
        state.setSkew(180.0);

        assertTrue(props.setSpriteProp(17, "member", Datum.CastMemberRef.of(1, 0)));

        assertFalse(state.isFlipH());
        assertFalse(state.isFlipV());
        assertEquals(0.0, state.getRotation());
        assertEquals(0.0, state.getSkew());
    }

    @Test
    void memberNumZeroCreatesExplicitEmptyOverride() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        assertTrue(props.setSpriteProp(23, "member", Datum.CastMemberRef.of(4, 88)));

        SpriteState state = registry.get(23);
        assertTrue(state.hasDynamicMember());

        assertTrue(props.setSpriteProp(23, "memberNum", Datum.ZERO));

        assertTrue(state.hasDynamicMember());
        assertEquals(0, state.getEffectiveCastMember());
    }

    @Test
    void memberZeroOnScoreBackedSpriteDoesNotFallBackToScoreMember() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        registry.getOrCreate(31, new ScoreChunk.ChannelData(
                1, 0, 0, 0, 0, 0,
                4, 88,
                0, 0, 10, 20, 30, 40,
                0, 0, 0, 0, 0, 0, 0
        ));

        assertTrue(props.setSpriteProp(31, "member", Datum.ZERO));

        SpriteState state = registry.get(31);
        assertTrue(state.hasDynamicMember());
        assertEquals(0, state.getEffectiveCastLib());
        assertEquals(0, state.getEffectiveCastMember());
    }

    @Test
    void unpuppetingAfterMemberZeroKeepsReleasedScoreSpriteHidden() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        SpriteState state = registry.getOrCreate(31, new ScoreChunk.ChannelData(
                1, 0, 0, 0, 0, 0,
                4, 88,
                0, 0, 10, 20, 30, 40,
                0, 0, 0, 0, 0, 0, 0
        ));

        assertTrue(props.setSpriteProp(31, "member", Datum.ZERO));
        assertTrue(props.setSpriteProp(31, "visible", Datum.ZERO));
        assertTrue(props.setSpriteProp(31, "puppet", Datum.ZERO));

        assertFalse(state.isVisible(),
                "releaseSprite sets member(0) before unpuppeting; this must not resurrect the score sprite");
        assertTrue(state.hasDynamicMember(),
                "released score-backed channels need the explicit member(0) override to suppress the score member");
        assertEquals(0, state.getEffectiveCastMember(),
                "released empty channels must not fall back to the previous score member");
    }

    @Test
    void memberZeroDoesNotPruneAttachedBehaviorInstances() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        SpriteState state = registry.getOrCreateDynamic(21);
        assertTrue(props.setSpriteProp(21, "member", Datum.CastMemberRef.of(3, 42)));

        Datum.ScriptInstance broker = new Datum.ScriptInstance(99, new LinkedHashMap<>());
        Datum.ScriptInstance behavior = new Datum.ScriptInstance(100, new LinkedHashMap<>());
        state.setScriptInstanceList(List.of(broker, behavior));

        assertTrue(props.setSpriteProp(21, "member", Datum.ZERO));

        assertEquals(2, state.getScriptInstanceList().size());
        assertTrue(state.getScriptInstanceList().contains(broker));
        assertTrue(state.getScriptInstanceList().contains(behavior));
    }

    @Test
    void replacingEquivalentScriptInstanceListPreservesRuntimeProcedureCallbacks() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        SpriteState state = registry.getOrCreateDynamic(21);
        Datum.ScriptInstance previousBroker = new Datum.ScriptInstance(99, new LinkedHashMap<>());
        Datum.PropList previousProcedures = new Datum.PropList();
        previousProcedures.put("mouseDown", true, new Datum.List(List.of(
                Datum.symbol("eventProcRoom"),
                Datum.of("room_interface"))));
        previousBroker.properties().put("pProcList", previousProcedures);
        state.setScriptInstanceList(List.of(previousBroker));

        Datum.ScriptInstance nextBroker = new Datum.ScriptInstance(99, new LinkedHashMap<>());
        Datum.PropList defaultProcedures = new Datum.PropList();
        defaultProcedures.put("mouseDown", true, new Datum.List(List.of(Datum.symbol("null"), Datum.ZERO)));
        nextBroker.properties().put("pProcList", defaultProcedures);

        assertTrue(props.setSpriteProp(21, "scriptInstanceList",
                new Datum.List(List.of(nextBroker))));

        assertEquals(List.of(nextBroker), state.getScriptInstanceList());
        Datum copied = ((Datum.PropList) nextBroker.properties().get("pProcList")).get("mouseDown");
        assertTrue(copied instanceof Datum.List);
        Datum.List callback = (Datum.List) copied;
        assertEquals("eventProcRoom", callback.items().get(0).toKeyName());
        assertEquals("room_interface", callback.items().get(1).toStr());
        assertEquals(21, nextBroker.properties().get("spriteNum").toInt());
    }

    @Test
    void replacingScriptInstanceListDoesNotPreserveEmptyProcedureTemplates() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        SpriteState state = registry.getOrCreateDynamic(22);
        Datum.ScriptInstance previousBroker = new Datum.ScriptInstance(99, new LinkedHashMap<>());
        Datum.PropList previousProcedures = new Datum.PropList();
        previousProcedures.put("mouseDown", true, new Datum.List(List.of(Datum.symbol("null"), Datum.ZERO)));
        previousBroker.properties().put("pProcList", previousProcedures);
        state.setScriptInstanceList(List.of(previousBroker));

        Datum.ScriptInstance nextBroker = new Datum.ScriptInstance(99, new LinkedHashMap<>());
        Datum.PropList defaultProcedures = new Datum.PropList();
        defaultProcedures.put("mouseDown", true, new Datum.List(List.of(Datum.symbol("null"), Datum.ZERO)));
        nextBroker.properties().put("pProcList", defaultProcedures);

        assertTrue(props.setSpriteProp(22, "scriptInstanceList",
                new Datum.List(List.of(nextBroker))));

        Datum kept = ((Datum.PropList) nextBroker.properties().get("pProcList")).get("mouseDown");
        assertTrue(kept instanceof Datum.List);
        Datum.List callback = (Datum.List) kept;
        assertEquals("null", callback.items().get(0).toKeyName());
        assertEquals(0, callback.items().get(1).toInt());
    }

    @Test
    void musTraceDoesNotEnableSpritePropertyLogging() {
        boolean oldDebugPlayback = DebugConfig.isDebugPlaybackEnabled();
        boolean oldPropertyTrace = DebugConfig.isPropertyTraceEnabled();
        boolean oldMusTrace = DebugConfig.isMusTraceEnabled();
        PrintStream oldOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try {
            DebugConfig.setDebugPlaybackEnabled(false);
            DebugConfig.setPropertyTraceEnabled(false);
            DebugConfig.setMusTraceEnabled(true);
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));

            SpriteRegistry registry = new SpriteRegistry();
            SpriteProperties props = new SpriteProperties(registry);
            assertTrue(props.setSpriteProp(17, "puppet", Datum.of(1)));
            assertTrue(props.setSpriteProp(17, "visible", Datum.of(0)));
            assertTrue(props.setSpriteProp(17, "loc", new Datum.Point(10, 20)));
        } finally {
            System.setOut(oldOut);
            DebugConfig.setDebugPlaybackEnabled(oldDebugPlayback);
            DebugConfig.setPropertyTraceEnabled(oldPropertyTrace);
            DebugConfig.setMusTraceEnabled(oldMusTrace);
        }

        assertFalse(captured.toString(StandardCharsets.UTF_8).contains("[SpriteProperties]"));
    }

    @Test
    void disablingPuppetOnEmptySpriteClearsRuntimeBehaviorsAndResetsReleasedChannelState() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        SpriteState state = registry.getOrCreateDynamic(23);
        Datum.ScriptInstance broker = new Datum.ScriptInstance(99, new LinkedHashMap<>());
        Datum.ScriptInstance behavior = new Datum.ScriptInstance(100, new LinkedHashMap<>());
        state.setScriptInstanceList(List.of(broker, behavior));
        state.setVisible(true);
        state.setBlend(30);
        state.setStretch(1);
        state.setCursor(4);
        state.setWidth(88);
        state.setHeight(44);

        assertTrue(props.setSpriteProp(23, "member", Datum.ZERO));
        assertTrue(props.setSpriteProp(23, "puppet", Datum.ZERO));

        assertEquals(List.of(), state.getScriptInstanceList());
        assertFalse(state.isVisible());
        assertEquals(100, state.getBlend());
        assertEquals(0, state.getStretch());
        assertEquals(0, state.getCursor());
        assertEquals(1, state.getWidth());
        assertEquals(1, state.getHeight());
        assertFalse(state.hasDynamicMember());
    }

    @Test
    void disablingPuppetOnDynamicMemberSpriteClearsStaleRoomMember() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        SpriteState state = registry.getOrCreateDynamic(47);
        Datum.ScriptInstance behavior = new Datum.ScriptInstance(100, new LinkedHashMap<>());
        state.setScriptInstanceList(List.of(behavior));
        state.setVisible(true);
        state.setWidth(88);
        state.setHeight(44);

        assertTrue(props.setSpriteProp(47, "member", Datum.CastMemberRef.of(12, 345)));
        assertTrue(state.hasDynamicMember());
        assertEquals(345, state.getEffectiveCastMember());

        assertTrue(props.setSpriteProp(47, "puppet", Datum.ZERO));

        assertFalse(state.isPuppet());
        assertFalse(state.hasDynamicMember());
        assertFalse(state.isVisible());
        assertEquals(List.of(), state.getScriptInstanceList());
        assertEquals(1, state.getWidth());
        assertEquals(1, state.getHeight());
    }

    @Test
    void disablingPuppetOnScoreBackedDynamicMemberSpriteFallsBackToScoreMember() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        SpriteState state = registry.getOrCreate(31, new ScoreChunk.ChannelData(
                1, 0, 0, 0, 0, 0,
                4, 88,
                0, 0, 10, 20, 30, 40,
                0, 0, 0, 0, 0, 0, 0
        ));

        Datum.ScriptInstance behavior = new Datum.ScriptInstance(100, new LinkedHashMap<>());
        state.setScriptInstanceList(List.of(behavior));

        assertTrue(props.setSpriteProp(31, "member", Datum.CastMemberRef.of(12, 345)));
        assertTrue(state.hasDynamicMember());
        assertEquals(345, state.getEffectiveCastMember());
        state.setWidth(120);
        state.setHeight(90);

        assertTrue(props.setSpriteProp(31, "puppet", Datum.ZERO));

        assertFalse(state.isPuppet());
        assertFalse(state.hasDynamicMember());
        assertEquals(4, state.getEffectiveCastLib());
        assertEquals(88, state.getEffectiveCastMember());
        assertTrue(state.isVisible());
        assertEquals(40, state.getWidth());
        assertEquals(30, state.getHeight());
        assertEquals(List.of(behavior), state.getScriptInstanceList());
    }

    @Test
    void disablingPuppetOnEmptyScoreSpriteKeepsExplicitEmptyOverrideAndClearsRuntimeBehaviors() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        SpriteState state = registry.getOrCreate(31, new ScoreChunk.ChannelData(
                1, 0, 0, 0, 0, 0,
                4, 88,
                0, 0, 10, 20, 30, 40,
                0, 0, 0, 0, 0, 0, 0
        ));
        Datum.ScriptInstance broker = new Datum.ScriptInstance(99, new LinkedHashMap<>());
        state.setScriptInstanceList(List.of(broker));
        state.setVisible(true);

        assertTrue(props.setSpriteProp(31, "member", Datum.ZERO));
        assertTrue(state.hasDynamicMember());
        assertEquals(0, state.getEffectiveCastMember());

        assertTrue(props.setSpriteProp(31, "puppet", Datum.ZERO));

        assertTrue(state.hasDynamicMember(),
                "member(0) before unpuppeting is an explicit empty-channel release, not a request to resurrect the score sprite");
        assertEquals(0, state.getEffectiveCastMember());
        assertFalse(state.isVisible());
        assertEquals(List.of(), state.getScriptInstanceList(),
                "released window channels must not keep stale registerProcedure brokers");
    }

    @Test
    void symbolicInkNameSetsNamedInkMode() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        assertTrue(props.setSpriteProp(17, "ink", Datum.symbol("addPin")));

        SpriteState state = registry.get(17);
        assertEquals(33, state.getInk());
    }

    @Test
    void moveableAndEditableSpritePropertiesRoundTripThroughSpriteState() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        assertEquals(0, props.getSpriteProp(17, "moveableSprite").toInt());
        assertEquals(0, props.getSpriteProp(17, "editable").toInt());

        assertTrue(props.setSpriteProp(17, "moveableSprite", Datum.TRUE));
        assertTrue(props.setSpriteProp(17, "editable", Datum.TRUE));

        SpriteState state = registry.get(17);
        assertTrue(state.isMoveableSprite());
        assertTrue(state.isEditable());
        assertEquals(1, props.getSpriteProp(17, "moveable").toInt());
        assertEquals(1, props.getSpriteProp(17, "editableText").toInt());

        assertTrue(props.setSpriteProp(17, "moveable", Datum.FALSE));
        assertTrue(props.setSpriteProp(17, "editableText", Datum.FALSE));

        assertFalse(state.isMoveableSprite());
        assertFalse(state.isEditable());
        assertEquals(0, props.getSpriteProp(17, "moveableSprite").toInt());
        assertEquals(0, props.getSpriteProp(17, "editable").toInt());
    }

    @Test
    void clearDynamicMemberBindingsDetachesOnlyMatchingSprites() {
        SpriteRegistry registry = new SpriteRegistry();

        SpriteState floor = registry.getOrCreateDynamic(11);
        floor.setDynamicMember(7, 10001);
        floor.setFlipH(true);
        floor.setRotation(180.0);
        floor.setSkew(180.0);

        SpriteState other = registry.getOrCreateDynamic(12);
        other.setDynamicMember(7, 10002);
        other.setFlipH(true);
        other.setRotation(180.0);
        other.setSkew(180.0);

        assertTrue(registry.clearDynamicMemberBindings(7, 10001));
        assertFalse(floor.hasDynamicMember());
        assertFalse(floor.isFlipH());
        assertEquals(0.0, floor.getRotation());
        assertEquals(0.0, floor.getSkew());
        assertTrue(other.hasDynamicMember());
        assertEquals(7, other.getEffectiveCastLib());
        assertEquals(10002, other.getEffectiveCastMember());
        assertTrue(other.isFlipH());
        assertEquals(180.0, other.getRotation());
        assertEquals(180.0, other.getSkew());
        assertEquals(1, registry.getRevision());
    }

    @Test
    void retiredScoreBackedRuntimeMemberRestoresAuthoredGeometryBeforeReuse() throws Exception {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);
        CastLibManager castLibManager = new CastLibManager(null, (castLib, fileName) -> {});
        CastLib castLib = new CastLib(7, null, null);
        injectCastLib(castLibManager, castLib);
        props.setCastLibManager(castLibManager);

        SpriteState state = registry.getOrCreate(9, new ScoreChunk.ChannelData(
                1, 0, 0, 0, 0, 0,
                3, 40,
                0, 0, 10, 20, 30, 40,
                0, 0, 0, 0, 0, 0, 0
        ));

        CastMember first = castLib.createDynamicMember("bitmap");
        Bitmap firstBitmap = new Bitmap(24, 18, 32);
        firstBitmap.fill(0xFFFFFFFF);
        assertTrue(first.setProp("image", new Datum.ImageRef(firstBitmap)));

        CastMember second = castLib.createDynamicMember("bitmap");
        Bitmap secondBitmap = new Bitmap(36, 22, 32);
        secondBitmap.fill(0xFFFFFFFF);
        assertTrue(second.setProp("image", new Datum.ImageRef(secondBitmap)));

        assertTrue(props.setSpriteProp(9, "member",
                Datum.CastMemberRef.of(7, first.getMemberNumber())));

        Datum.ScriptInstance broker = new Datum.ScriptInstance(77, new LinkedHashMap<>());
        state.setScriptInstanceList(List.of(broker));

        state.setWidth(160);
        state.setHeight(120);
        assertTrue(state.hasSizeChanged());

        CastMember.setMemberSlotRetiredCallback(registry::clearDynamicMemberBindings);
        try {
            first.erase();
        } finally {
            CastMember.setMemberSlotRetiredCallback(null);
        }

        assertFalse(state.hasDynamicMember());
        assertEquals(40, state.getWidth());
        assertEquals(30, state.getHeight());
        assertFalse(state.hasSizeChanged());
        assertEquals(List.of(broker), state.getScriptInstanceList());

        CastMember reused = castLib.createDynamicMember("bitmap");
        assertSame(first, reused);
        assertEquals(first.getMemberNumber(), reused.getMemberNumber());
        reused.setBitmapDirectly(secondBitmap);

        assertTrue(props.setSpriteProp(9, "member",
                Datum.CastMemberRef.of(7, reused.getMemberNumber())));

        assertEquals(36, state.getWidth());
        assertEquals(22, state.getHeight());
        assertFalse(state.hasSizeChanged());
    }

    @Test
    void scoreSpriteRebindPreservesRuntimeScriptInstancesWhenMemberChanges() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteState state = registry.getOrCreate(9, new ScoreChunk.ChannelData(
                1, 0, 0, 0, 0, 0,
                3, 40,
                0, 0, 10, 20, 30, 40,
                0, 0, 0, 0, 0, 0, 0
        ));

        Datum.ScriptInstance scriptInstance = new Datum.ScriptInstance(77, new LinkedHashMap<>());
        state.setScriptInstanceList(List.of(scriptInstance));
        state.setForeColor(0x123456);
        state.setBackColor(0x654321);
        state.setRotation(180.0);
        state.setSkew(180.0);
        state.setFlipH(true);
        state.setFlipV(true);
        state.setCursor(4);
        state.setWidth(88);
        state.setHeight(66);

        registry.updateFromScore(9, new ScoreChunk.ChannelData(
                1, 0, 0, 0, 0, 0,
                4, 41,
                0, 0, 50, 60, 70, 80,
                0, 0, 0, 0, 0, 0, 0
        ));

        assertEquals(4, state.getEffectiveCastLib());
        assertEquals(41, state.getEffectiveCastMember());
        assertEquals(List.of(scriptInstance), state.getScriptInstanceList());
        assertSame(scriptInstance, state.getScriptInstanceList().get(0));
        assertEquals(80, state.getWidth());
        assertEquals(70, state.getHeight());
        assertEquals(0.0, state.getRotation());
        assertEquals(0.0, state.getSkew());
        assertFalse(state.isFlipH());
        assertFalse(state.isFlipV());
        assertEquals(0, state.getCursor());
        assertFalse(state.hasForeColor());
        assertFalse(state.hasBackColor());
        assertEquals(1, registry.getRevision());
    }

    @Test
    void spriteColorPropertiesPreserveDirectorColorSource() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        assertTrue(props.setSpriteProp(7, "bgColor", new Datum.PaletteIndexColor(0)));
        SpriteState state = registry.get(7);
        assertNotNull(state);
        assertEquals(0, state.getBackColor());
        assertEquals(SpriteColorSource.PALETTE_INDEX, state.getBackColorSource());

        assertTrue(props.setSpriteProp(7, "bgColor", Datum.of(0)));
        assertEquals(0, state.getBackColor());
        assertEquals(SpriteColorSource.PALETTE_INDEX, state.getBackColorSource());

        assertTrue(props.setSpriteProp(7, "bgColor", new Datum.Color(0, 0, 0)));
        assertEquals(0, state.getBackColor());
        assertEquals(SpriteColorSource.RGB, state.getBackColorSource());
    }

    @Test
    void memberAssignmentDoesNotOverrideExplicitSpriteSize() throws Exception {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);
        CastLibManager castLibManager = new CastLibManager(null, (castLib, fileName) -> {});
        CastLib castLib = new CastLib(3, null, null);
        injectCastLib(castLibManager, castLib);

        CastMember preview = castLib.createDynamicMember("bitmap");
        Bitmap previewBitmap = new Bitmap(24, 18, 32);
        previewBitmap.fill(0xFFFFFFFF);
        assertTrue(preview.setProp("image", new Datum.ImageRef(previewBitmap)));

        props.setCastLibManager(castLibManager);

        SpriteState state = registry.getOrCreateDynamic(41);
        state.setWidth(160);
        state.setHeight(120);
        assertTrue(state.hasSizeChanged());

        assertTrue(props.setSpriteProp(41, "member",
                Datum.CastMemberRef.of(3, preview.getMemberNumber())));

        assertEquals(160, state.getWidth());
        assertEquals(120, state.getHeight());
        assertTrue(state.hasSizeChanged());
    }

    @Test
    void setMemberMethodResetsRuntimePreviewToIntrinsicSize() throws Exception {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);
        CastLibManager castLibManager = new CastLibManager(null, (castLib, fileName) -> {});
        CastLib castLib = new CastLib(3, null, null);
        injectCastLib(castLibManager, castLib);

        CastMember preview = castLib.createDynamicMember("bitmap");
        Bitmap previewBitmap = new Bitmap(24, 18, 32);
        previewBitmap.fill(0xFFFFFFFF);
        assertTrue(preview.setProp("image", new Datum.ImageRef(previewBitmap)));

        props.setCastLibManager(castLibManager);

        SpriteState state = registry.getOrCreateDynamic(41);
        state.setWidth(160);
        state.setHeight(120);
        assertTrue(state.hasSizeChanged());

        assertTrue(props.setSpriteMember(41,
                Datum.CastMemberRef.of(3, preview.getMemberNumber())));

        assertEquals(24, state.getWidth());
        assertEquals(18, state.getHeight());
        assertFalse(state.hasSizeChanged());
    }

    @Test
    void encodedMemberAssignmentDecodesCastLibAndMemberSlot() throws Exception {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);
        CastLibManager castLibManager = new CastLibManager(null, (castLib, fileName) -> {});
        CastLib castLib = new CastLib(3, null, null);
        injectCastLib(castLibManager, castLib);

        CastMember preview = castLib.createDynamicMember("bitmap");
        Bitmap previewBitmap = new Bitmap(24, 18, 32);
        previewBitmap.fill(0xFFFFFFFF);
        assertTrue(preview.setProp("image", new Datum.ImageRef(previewBitmap)));

        props.setCastLibManager(castLibManager);

        int encodedSlot = (3 << 16) | preview.getMemberNumber();
        assertTrue(props.setSpriteProp(41, "member", Datum.of(encodedSlot)));

        SpriteState state = registry.get(41);
        assertEquals(3, state.getEffectiveCastLib());
        assertEquals(preview.getMemberNumber(), state.getEffectiveCastMember());
        assertEquals(24, state.getWidth());
        assertEquals(18, state.getHeight());
    }

    @Test
    void blankingDynamicMemberNameDoesNotDetachBoundSprite() throws Exception {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);
        CastLibManager castLibManager = new CastLibManager(null, (castLib, fileName) -> {});
        CastLib castLib = new CastLib(7, null, null);
        injectCastLib(castLibManager, castLib);
        props.setCastLibManager(castLibManager);

        CastMember preview = castLib.createDynamicMember("bitmap");
        assertTrue(props.setSpriteProp(51, "member",
                Datum.CastMemberRef.of(7, preview.getMemberNumber())));

        CastMember.setMemberSlotRetiredCallback(registry::clearDynamicMemberBindings);
        try {
            assertTrue(preview.setProp("name", Datum.of("handcontainer_temp")));
            assertTrue(preview.setProp("name", Datum.EMPTY_STRING));
        } finally {
            CastMember.setMemberSlotRetiredCallback(null);
        }

        SpriteState state = registry.get(51);
        assertTrue(state.hasDynamicMember());
        assertEquals(7, state.getEffectiveCastLib());
        assertEquals(preview.getMemberNumber(), state.getEffectiveCastMember());
    }

    @Test
    void spriteBoundsUseRegistrationPointAdjustedStageEdges() throws Exception {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);
        CastLibManager castLibManager = new CastLibManager(null, (castLib, fileName) -> {});
        CastLib castLib = new CastLib(7, null, null);
        injectCastLib(castLibManager, castLib);
        props.setCastLibManager(castLibManager);

        CastMember cloud = castLib.createDynamicMember("bitmap");
        cloud.setBitmapDirectly(new Bitmap(21, 22, 32));
        assertTrue(cloud.setProp("regPoint", new Datum.Point(21, 0)));

        assertTrue(props.setSpriteProp(41, "member",
                Datum.CastMemberRef.of(7, cloud.getMemberNumber())));
        assertTrue(props.setSpriteProp(41, "loc", new Datum.Point(100, 50)));

        assertEquals(79, props.getSpriteProp(41, "left").toInt());
        assertEquals(50, props.getSpriteProp(41, "top").toInt());
        assertEquals(100, props.getSpriteProp(41, "right").toInt());
        assertEquals(72, props.getSpriteProp(41, "bottom").toInt());
        assertEquals(new Datum.Rect(79, 50, 100, 72), props.getSpriteProp(41, "rect"));
    }

    @Test
    void entryCloudTurnPointUsesRegistrationAdjustedRightEdge() throws Exception {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);
        CastLibManager castLibManager = new CastLibManager(null, (castLib, fileName) -> {});
        CastLib castLib = new CastLib(7, null, null);
        injectCastLib(castLibManager, castLib);
        props.setCastLibManager(castLibManager);

        CastMember cloud = castLib.createDynamicMember("bitmap");
        Bitmap cloudCanvas = new Bitmap(21, 60, 8);
        cloudCanvas.setAnchorPoint(10, 30);
        assertTrue(cloud.setProp("image", new Datum.ImageRef(cloudCanvas)));

        assertTrue(props.setSpriteProp(41, "member",
                Datum.CastMemberRef.of(7, cloud.getMemberNumber())));
        assertTrue(props.setSpriteProp(41, "loc", new Datum.Point(319, 120)));
        assertTrue(props.setSpriteProp(41, "width", Datum.of(21)));
        assertTrue(props.setSpriteProp(41, "height", Datum.of(60)));

        assertEquals(309, props.getSpriteProp(41, "left").toInt());
        assertEquals(330, props.getSpriteProp(41, "right").toInt());

        assertTrue(props.setSpriteProp(41, "locH", Datum.of(320)));

        assertEquals(310, props.getSpriteProp(41, "left").toInt());
        assertEquals(331, props.getSpriteProp(41, "right").toInt());
    }

    @Test
    void rectSetterPreservesDirectorRegistrationPointSemantics() throws Exception {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);
        CastLibManager castLibManager = new CastLibManager(null, (castLib, fileName) -> {});
        CastLib castLib = new CastLib(7, null, null);
        injectCastLib(castLibManager, castLib);
        props.setCastLibManager(castLibManager);

        CastMember cloud = castLib.createDynamicMember("bitmap");
        cloud.setBitmapDirectly(new Bitmap(21, 22, 32));
        assertTrue(cloud.setProp("regPoint", new Datum.Point(21, 0)));

        assertTrue(props.setSpriteProp(41, "member",
                Datum.CastMemberRef.of(7, cloud.getMemberNumber())));

        assertTrue(props.setSpriteProp(41, "rect", new Datum.Rect(79, 50, 100, 72)));

        assertEquals(100, props.getSpriteProp(41, "locH").toInt());
        assertEquals(50, props.getSpriteProp(41, "locV").toInt());
        assertEquals(21, props.getSpriteProp(41, "width").toInt());
        assertEquals(22, props.getSpriteProp(41, "height").toInt());
        assertEquals(new Datum.Rect(79, 50, 100, 72), props.getSpriteProp(41, "rect"));
    }

    @Test
    void locSetterAcceptsDirectorPointLists() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        assertTrue(props.setSpriteProp(7, "loc",
                Datum.list(List.of(Datum.of(123), Datum.of(45)))));

        assertEquals(123, props.getSpriteProp(7, "locH").toInt());
        assertEquals(45, props.getSpriteProp(7, "locV").toInt());
        assertEquals(new Datum.Point(123, 45), props.getSpriteProp(7, "loc"));
    }

    @Test
    void spriteImageUsesResolvedMemberImage() {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteProperties props = new SpriteProperties(registry);

        CastMember member = new CastMember(7, 42, MemberType.BITMAP);
        Bitmap bitmap = new Bitmap(3, 2, 32);
        bitmap.fill(0xFF123456);
        assertTrue(member.setProp("image", new Datum.ImageRef(bitmap)));

        props.setCastLibManager(new ResolvedOnlyCastLibManager(member));
        assertTrue(props.setSpriteProp(5, "member", Datum.CastMemberRef.of(7, 42)));

        Datum image = props.getSpriteProp(5, "image");

        assertTrue(image instanceof Datum.ImageRef);
        assertEquals(0xFF123456, ((Datum.ImageRef) image).bitmap().getPixel(0, 0));
    }

    @SuppressWarnings("unchecked")
    private static void injectCastLib(CastLibManager castLibManager, CastLib castLib) throws Exception {
        Field castLibsField = CastLibManager.class.getDeclaredField("castLibs");
        castLibsField.setAccessible(true);
        ((Map<Integer, CastLib>) castLibsField.get(castLibManager)).put(castLib.getNumber(), castLib);
    }

    private static final class ResolvedOnlyCastLibManager extends CastLibManager {
        private final CastMember member;

        private ResolvedOnlyCastLibManager(CastMember member) {
            super(null, (castLib, fileName) -> {});
            this.member = member;
        }

        @Override
        public CastMember getDynamicMember(int castLibNumber, int memberNumber) {
            return null;
        }

        @Override
        public CastMember resolveMember(int castLibNumber, int memberNumber) {
            return castLibNumber == member.getCastLibId().value()
                    && memberNumber == member.getMemberNumber()
                    ? member
                    : null;
        }
    }
}
