package com.libreshockwave.player.cast;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.builtin.cast.CastLibProvider;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.opcode.dispatch.ImageMethodDispatcher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class CastLibManagerPaletteTest {

    @Test
    void globalHandlerLookupOnlyTreatsMovieScriptsAsGlobal() {
        assertEquals(true, CastLibManager.isGlobalHandlerScriptType(com.libreshockwave.chunks.ScriptChunk.ScriptType.MOVIE_SCRIPT));
        assertEquals(false, CastLibManager.isGlobalHandlerScriptType(com.libreshockwave.chunks.ScriptChunk.ScriptType.SCORE));
        assertEquals(false, CastLibManager.isGlobalHandlerScriptType(com.libreshockwave.chunks.ScriptChunk.ScriptType.BEHAVIOR));
        assertEquals(false, CastLibManager.isGlobalHandlerScriptType(com.libreshockwave.chunks.ScriptChunk.ScriptType.PARENT));
        assertEquals(false, CastLibManager.isGlobalHandlerScriptType(com.libreshockwave.chunks.ScriptChunk.ScriptType.UNKNOWN));
    }

    @Test
    void duplicatePaletteMembersStayResolvableForWindowBuffers() throws Exception {
        CastLibManager manager = new CastLibManager(null, null);
        CastLib castLib = new CastLib(1, null, null);
        installCastLib(manager, castLib);

        CastMember sourcePaletteMember = castLib.createDynamicMember("palette");
        sourcePaletteMember.setProp("name", Datum.of("interface palette_messenger"));
        Palette sourcePalette = new Palette(new int[]{0x112233, 0x445566, 0x778899}, "Messenger UI");
        sourcePaletteMember.setPaletteData(sourcePalette);

        CastMember duplicatePaletteMember = castLib.createDynamicMember("palette");
        duplicatePaletteMember.setProp("name", Datum.of("interface palette_messengerDuplicate"));

        Datum targetRef = Datum.CastMemberRef.of(1, duplicatePaletteMember.getMemberNumber());
        manager.callMemberMethod(1, sourcePaletteMember.getMemberNumber(), "duplicate", java.util.List.of(targetRef));

        Palette resolvedByMember = manager.getMemberPalette(1, duplicatePaletteMember.getMemberNumber());
        assertNotNull(resolvedByMember);
        assertNotSame(sourcePalette, resolvedByMember);
        assertEquals(0x112233, resolvedByMember.getColor(0));

        Palette resolvedByName = manager.resolvePaletteByName("interface palette_messengerDuplicate");
        assertNotNull(resolvedByName);
        assertSame(resolvedByMember, resolvedByName);
    }

    @Test
    void emptyLayoutPaletteDuplicateResolvesFromSourcePaletteWhenCreatedTooEarly() throws Exception {
        CastLibManager manager = new CastLibManager(null, null);
        CastLib castLib = new CastLib(3, null, null);
        installCastLib(manager, castLib);

        CastMember duplicatePaletteMember = castLib.createDynamicMember("palette");
        duplicatePaletteMember.setProp("name", Datum.of("interface palette Duplicate"));

        CastMember sourcePaletteMember = castLib.createDynamicMember("palette");
        sourcePaletteMember.setProp("name", Datum.of("interface palette"));
        Palette sourcePalette = new Palette(new int[]{0xC8D8DE, 0x4F6F7C, 0x1A1A1A}, "Interface");
        sourcePaletteMember.setPaletteData(sourcePalette);

        Palette resolvedByMember = manager.getMemberPalette(3, duplicatePaletteMember.getMemberNumber());
        assertNotNull(resolvedByMember);
        assertNotSame(sourcePalette, resolvedByMember);
        assertEquals(0xC8D8DE, resolvedByMember.getColor(0));
        assertEquals(0x4F6F7C, duplicatePaletteMember.getPaletteData().getColor(1));

        Palette resolvedByName = manager.resolvePaletteByName("interface palette Duplicate");
        assertNotNull(resolvedByName);
        assertEquals(0x1A1A1A, resolvedByName.getColor(2));
    }

    @Test
    void duplicatePaletteMembersAcceptEncodedSlotTargets() throws Exception {
        CastLibManager manager = new CastLibManager(null, null);
        CastLib castLib = new CastLib(3, null, null);
        installCastLib(manager, castLib);

        CastMember sourcePaletteMember = castLib.createDynamicMember("palette");
        sourcePaletteMember.setProp("name", Datum.of("purse_rclr_palette"));
        Palette sourcePalette = new Palette(new int[]{0x010203, 0xAABBCC}, "Purse");
        sourcePaletteMember.setPaletteData(sourcePalette);

        CastMember duplicatePaletteMember = castLib.createDynamicMember("palette");
        duplicatePaletteMember.setProp("name", Datum.of("purse_rclr_paletteDuplicate"));

        Datum targetSlot = Datum.of(duplicatePaletteMember.getSlotNumber());
        Datum duplicateResult = manager.callMemberMethod(
                3,
                sourcePaletteMember.getMemberNumber(),
                "duplicate",
                java.util.List.of(targetSlot));

        assertEquals(Datum.CastMemberRef.of(3, duplicatePaletteMember.getMemberNumber()), duplicateResult);

        Palette resolvedByMember = manager.getMemberPalette(3, duplicatePaletteMember.getMemberNumber());
        assertNotNull(resolvedByMember);
        assertNotSame(sourcePalette, resolvedByMember);
        assertEquals(0x010203, resolvedByMember.getColor(0));
    }

    @Test
    void duplicatePaletteMembersCanCopyArgumentIntoEmptyReceiver() throws Exception {
        CastLibManager manager = new CastLibManager(null, null);
        CastLib castLib = new CastLib(1, null, null);
        installCastLib(manager, castLib);

        CastMember sourcePaletteMember = castLib.createDynamicMember("palette");
        sourcePaletteMember.setProp("name", Datum.of("nav_ui_palette"));
        Palette sourcePalette = new Palette(new int[]{0xD4DDE1, 0x9BBCC7}, "Navigator UI");
        sourcePaletteMember.setPaletteData(sourcePalette);

        CastMember duplicatePaletteMember = castLib.createDynamicMember("palette");
        duplicatePaletteMember.setProp("name", Datum.of("nav_ui_paletteDuplicate"));

        Datum sourceRef = Datum.CastMemberRef.of(1, sourcePaletteMember.getMemberNumber());
        Datum duplicateResult = manager.callMemberMethod(
                1,
                duplicatePaletteMember.getMemberNumber(),
                "duplicate",
                java.util.List.of(sourceRef));

        assertEquals(Datum.CastMemberRef.of(1, duplicatePaletteMember.getMemberNumber()), duplicateResult);

        Palette resolvedByMember = manager.getMemberPalette(1, duplicatePaletteMember.getMemberNumber());
        assertNotNull(resolvedByMember);
        assertNotSame(sourcePalette, resolvedByMember);
        assertEquals(0xD4DDE1, resolvedByMember.getColor(0));
    }

    @Test
    void memberDuplicateWithoutTargetCreatesIndependentBitmapThatCanRemapPaletteRef() throws Exception {
        CastLibManager manager = new CastLibManager(null, null);
        CastLib castLib = new CastLib(1, null, null);
        installCastLib(manager, castLib);

        Palette defaultPalette = new Palette(new int[]{0xFFFFFF, 0xE7F700}, "default");
        Palette parkPalette = new Palette(new int[]{0xFFFFFF, 0x996600}, "park");

        CastMember paletteMember = castLib.createDynamicMember("palette");
        paletteMember.setPaletteData(parkPalette);

        Bitmap sourceBitmap = new Bitmap(1, 1, 8);
        sourceBitmap.setImagePalette(defaultPalette);
        sourceBitmap.setPixelPaletteIndex(0, 0, 1, 0xFFE7F700);

        CastMember sourceMember = castLib.createDynamicMember("bitmap");
        sourceMember.setBitmapDirectly(sourceBitmap);

        Datum duplicated = manager.callMemberMethod(
                1, sourceMember.getMemberNumber(), "duplicate", List.of());
        Datum.CastMemberRef duplicateRef = assertInstanceOf(Datum.CastMemberRef.class, duplicated);
        CastMember duplicateMember = castLib.getMember(duplicateRef.memberNum());
        assertNotNull(duplicateMember);

        Bitmap duplicateBitmap = duplicateMember.getBitmap();
        assertNotNull(duplicateBitmap);
        assertArrayEquals(new byte[]{1}, duplicateBitmap.getPaletteIndices());

        CastLibProvider.setProvider(manager);
        try {
            Datum image = duplicateMember.getProp("image");
            ImageMethodDispatcher.setProperty(
                    assertInstanceOf(Datum.ImageRef.class, image),
                    "paletteRef",
                    Datum.CastMemberRef.of(1, paletteMember.getMemberNumber()));
        } finally {
            CastLibProvider.clearProvider();
        }

        assertEquals(0xFF996600, duplicateBitmap.getPixel(0, 0),
                "member.duplicate().image must keep index provenance so paletteRef can recolor it");
        assertEquals(0xFFE7F700, sourceMember.getBitmap().getPixel(0, 0),
                "the duplicated member image must be independent from the source member image");
    }

    @Test
    void puppetPaletteIntegerResolvesPaletteMemberNumber() throws Exception {
        CastLibManager manager = new CastLibManager(null, null);
        CastLib castLib = new CastLib(1, null, null);
        installCastLib(manager, castLib);

        CastMember paletteMember = castLib.createDynamicMember("palette");
        Palette palette = new Palette(new int[]{0x112233, 0x445566}, "Director Palette");
        paletteMember.setPaletteData(palette);

        CastLibProvider.setProvider(manager);
        Datum.setPuppetPalette(null);
        try {
            new LingoVM(null).callHandler("puppetPalette", List.of(Datum.of(paletteMember.getMemberNumber())));
            assertSame(palette, Datum.getPuppetPalette());
        } finally {
            Datum.setPuppetPalette(null);
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void rawRegistrySlotsCanResolveUniqueDefinitionMemberWhenNameLookupCollides() throws Exception {
        CastLibManager manager = new CastLibManager(null, null);
        CastLib castLib = new CastLib(2, null, null);
        installCastLib(manager, castLib);
        castLib.load();

        installMemberChunk(castLib, 162, MemberType.TEXT, "member_162");
        installMemberChunk(castLib, 2650, MemberType.BITMAP, "layout.element");

        Datum broadLookup = manager.getRegistryMemberByName(0, "layout.element");
        assertEquals(Datum.CastMemberRef.of(2, 2650), broadLookup);
        assertEquals((2 << 16) | 162,
                manager.resolveRawRegistryMemberSlot("layout.element", 162));
    }

    @SuppressWarnings("unchecked")
    private static void installCastLib(CastLibManager manager, CastLib castLib) throws Exception {
        Field initializedField = CastLibManager.class.getDeclaredField("initialized");
        initializedField.setAccessible(true);
        initializedField.setBoolean(manager, true);

        Field castLibsField = CastLibManager.class.getDeclaredField("castLibs");
        castLibsField.setAccessible(true);
        Map<Integer, CastLib> castLibs = (Map<Integer, CastLib>) castLibsField.get(manager);
        castLibs.put(castLib.getNumber(), castLib);
    }

    @SuppressWarnings("unchecked")
    private static void installMemberChunk(
            CastLib castLib,
            int memberNumber,
            MemberType memberType,
            String name) throws Exception {
        Field memberChunksField = CastLib.class.getDeclaredField("memberChunks");
        memberChunksField.setAccessible(true);
        Map<Integer, CastMemberChunk> memberChunks =
                (Map<Integer, CastMemberChunk>) memberChunksField.get(castLib);
        memberChunks.put(memberNumber, new CastMemberChunk(
                null,
                new ChunkId(memberNumber),
                memberType,
                0,
                0,
                new byte[0],
                new byte[0],
                name,
                0,
                0,
                0));
    }
}
