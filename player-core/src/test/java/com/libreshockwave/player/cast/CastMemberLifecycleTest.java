package com.libreshockwave.player.cast;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CastMemberLifecycleTest {

    @Test
    void eraseClearsDynamicMemberTextAndName() {
        CastMember member = new CastMember(1, 10001, MemberType.TEXT);
        member.setProp("name", Datum.of("room_status"));
        member.setProp("text", Datum.of("hello"));

        Datum result = member.callMethod("erase", List.of());

        assertEquals(1, result.toInt());
        assertEquals("", member.getName());
        assertEquals("", member.getTextContent());
        assertEquals("empty", member.getProp("type").toKeyName());
        assertTrue(member.getProp("type").isSymbol());
    }

    @Test
    void memberTypePropertyReturnsDirectorSymbol() {
        CastMember bitmap = new CastMember(1, 10001, MemberType.BITMAP);
        Datum type = bitmap.getProp("type");

        assertTrue(type.isSymbol());
        assertEquals("bitmap", type.toKeyName());
    }

    @Test
    void textMembersReportDirectorFieldType() {
        CastMember field = new CastMember(1, 10001, MemberType.TEXT);

        Datum type = field.getProp("type");

        assertTrue(type.isSymbol());
        assertEquals("field", type.toKeyName());
    }

    @Test
    void parsedFieldValueIsCachedUntilTextChanges() {
        CastMember field = new CastMember(1, 10001, MemberType.TEXT);
        LingoVM vm = new LingoVM(null);

        field.setDynamicText("[#foo: 7]");
        Datum first = field.getParsedTextValue(vm);
        Datum second = field.getParsedTextValue(vm);

        assertSame(first, second);
        assertEquals(7, ((Datum.PropList) first).get("foo").toInt());

        field.setDynamicText("[#foo: 8]");
        Datum third = field.getParsedTextValue(vm);

        assertNotSame(first, third);
        assertEquals(8, ((Datum.PropList) third).get("foo").toInt());
    }

    @Test
    void paletteSetterRemapsDynamicBitmapMembersWithoutFileBacking() {
        Palette oldPalette = new Palette(new int[]{0xFFFFFF, 0x6C5230}, "old");
        Palette newPalette = new Palette(new int[]{0xFFFFFF, 0xC49A5A}, "new");

        Bitmap wrapper = new Bitmap(1, 1, 32);
        wrapper.setImagePalette(oldPalette);
        wrapper.setPixel(0, 0, 0xFF6C5230);

        CastMember member = new CastMember(1, 10001, MemberType.BITMAP);
        member.setBitmapDirectly(wrapper);

        CastMember.setPaletteResolver((castLib, memberNum) ->
                castLib == 9 && memberNum == 5 ? newPalette : null);
        try {
            assertTrue(member.setProp("palette", Datum.CastMemberRef.of(9, 5)));
            assertEquals(0xFFC49A5A, member.getBitmap().getPixel(0, 0));
            assertSame(newPalette, member.getBitmap().getImagePalette());

            Datum palette = member.getProp("palette");
            assertInstanceOf(Datum.CastMemberRef.class, palette);
            assertEquals(9, ((Datum.CastMemberRef) palette).castLibNum());
            assertEquals(5, ((Datum.CastMemberRef) palette).memberNum());
        } finally {
            CastMember.setPaletteResolver(null);
        }
    }

    @Test
    void blankingDynamicBitmapNameDoesNotRetireTheSlot() {
        CastMember member = new CastMember(7, 10001, MemberType.BITMAP);
        AtomicInteger retiredSlot = new AtomicInteger(-1);

        CastMember.setMemberSlotRetiredCallback((castLib, memberNum) -> {
            retiredSlot.set((castLib << 16) | memberNum);
        });
        try {
            member.setProp("name", Datum.of("bb_tempworld"));
            member.setProp("name", Datum.EMPTY_STRING);

            assertEquals("", member.getName());
            assertEquals(-1, retiredSlot.get());
        } finally {
            CastMember.setMemberSlotRetiredCallback(null);
        }
    }

    @Test
    void createDynamicMemberReusesFirstErasedRuntimeSlot() {
        CastLib castLib = new CastLib(4, null, null);

        CastMember first = castLib.createDynamicMember("bitmap");
        CastMember second = castLib.createDynamicMember("text");

        assertEquals(10000, first.getMemberNumber());
        assertEquals(10001, second.getMemberNumber());

        first.erase();

        CastMember reused = castLib.createDynamicMember("palette");

        assertSame(first, reused);
        assertEquals(10000, reused.getMemberNumber());
        assertEquals("palette", reused.getProp("type").toKeyName());
        assertTrue(reused.getProp("type").isSymbol());
    }

    @Test
    void duplicateAuthoredMemberNamesResolveToLatestSlot() throws Exception {
        CastLib castLib = new CastLib(4, null, null);
        CastMemberChunk placeholder = createTextXtraChunk(42, "shared_button_ok");
        CastMemberChunk replacement = createTextXtraChunk(488, "shared_button_ok");
        installAuthoredChunk(castLib, 42, placeholder);
        installAuthoredChunk(castLib, 488, replacement);

        assertSame(replacement, castLib.findMemberByName("shared_button_ok"));
        assertEquals(488, castLib.getMemberByName("shared_button_ok").getMemberNumber());
    }

    @Test
    void globalMemberLookupPrefersVisibleExternalDuplicateOverTransparentBootstrapPlaceholder()
            throws Exception {
        CastLib bootstrap = new CastLib(1, null, null);
        CastLib external = new CastLib(2, null, null);

        CastMemberChunk placeholderChunk = createBitmapChunk(17, "shared_button_ok");
        installAuthoredChunk(bootstrap, 5, placeholderChunk);
        CastMember placeholder = new CastMember(1, 5, placeholderChunk, null);
        Bitmap transparent = new Bitmap(2, 1, 32, new int[]{0x00000000, 0x00000000});
        transparent.setNativeAlpha(true);
        placeholder.setBitmapDirectly(transparent);
        installRuntimeMember(bootstrap, 5, placeholder);

        CastMemberChunk visibleChunk = createBitmapChunk(1706, "shared_button_ok");
        installAuthoredChunk(external, 488, visibleChunk);
        CastMember visible = new CastMember(2, 488, visibleChunk, null);
        visible.setBitmapDirectly(new Bitmap(2, 1, 32, new int[]{0xFFFFFFFF, 0xFF000000}));
        installRuntimeMember(external, 488, visible);

        CastLibManager manager = new CastLibManager(null, null);
        installCastLib(manager, 1, bootstrap);
        installCastLib(manager, 2, external);

        Datum resolved = manager.getMemberByName(0, "shared_button_ok");

        assertInstanceOf(Datum.CastMemberRef.class, resolved);
        assertEquals(2, ((Datum.CastMemberRef) resolved).castLibNum());
        assertEquals(488, ((Datum.CastMemberRef) resolved).memberNum());
    }

    @Test
    void mediaCopyTreatsTextXtraSourcesAsFields() {
        CastMemberChunk sourceChunk = createTextXtraChunk(42, "grunge_barrel.props");
        CastMember source = new CastMember(11, 42, sourceChunk, null);
        assertTrue(source.setProp("text", Datum.of("[#a: [#blend: 80]]")));

        CastMember target = new CastMember(2, 10000, MemberType.TEXT);
        CastMember.setMemberResolver((castLib, memberNum) ->
                castLib == 11 && memberNum == 42 ? source : null);
        try {
            assertTrue(target.setProp("media", Datum.CastMemberRef.of(11, 42)));
        } finally {
            CastMember.setMemberResolver(null);
        }

        assertEquals("[#a: [#blend: 80]]", target.getTextContent());
        assertEquals("field", target.getProp("type").toKeyName());
    }

    @Test
    void textXtraTargetsAcceptStringMediaAssignments() {
        CastMember target = new CastMember(11, 99, createTextXtraChunk(99, "dynamic.props"), null);

        assertTrue(target.setProp("media", Datum.of("[#foo: 1]")));
        assertEquals("[#foo: 1]", target.getTextContent());
        assertEquals("field", target.getProp("type").toKeyName());
    }

    private static CastMemberChunk createTextXtraChunk(int memberNum, String name) {
        byte[] textXtraSpecificData = new byte[]{0, 0, 0, 0, 't', 'e', 'x', 't'};
        return new CastMemberChunk(
                null,
                new ChunkId(memberNum),
                MemberType.XTRA,
                0,
                textXtraSpecificData.length,
                new byte[0],
                textXtraSpecificData,
                name,
                0,
                0,
                0);
    }

    private static CastMemberChunk createBitmapChunk(int memberNum, String name) {
        return new CastMemberChunk(
                null,
                new ChunkId(memberNum),
                MemberType.BITMAP,
                0,
                0,
                new byte[0],
                new byte[28],
                name,
                0,
                0,
                0);
    }

    @SuppressWarnings("unchecked")
    private static void installAuthoredChunk(CastLib castLib, int memberNumber, CastMemberChunk chunk)
            throws Exception {
        Field memberChunksField = CastLib.class.getDeclaredField("memberChunks");
        memberChunksField.setAccessible(true);
        Map<Integer, CastMemberChunk> memberChunks =
                (Map<Integer, CastMemberChunk>) memberChunksField.get(castLib);
        memberChunks.put(memberNumber, chunk);
    }

    @SuppressWarnings("unchecked")
    private static void installRuntimeMember(CastLib castLib, int memberNumber, CastMember member)
            throws Exception {
        Field membersField = CastLib.class.getDeclaredField("members");
        membersField.setAccessible(true);
        Map<Integer, CastMember> members = (Map<Integer, CastMember>) membersField.get(castLib);
        members.put(memberNumber, member);
    }

    @SuppressWarnings("unchecked")
    private static void installCastLib(CastLibManager manager, int castLibNumber, CastLib castLib)
            throws Exception {
        Field castLibsField = CastLibManager.class.getDeclaredField("castLibs");
        castLibsField.setAccessible(true);
        Map<Integer, CastLib> castLibs = (Map<Integer, CastLib>) castLibsField.get(manager);
        castLibs.put(castLibNumber, castLib);
    }
}
