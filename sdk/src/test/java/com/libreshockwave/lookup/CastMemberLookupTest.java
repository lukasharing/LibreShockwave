package com.libreshockwave.lookup;

import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastChunk;
import com.libreshockwave.chunks.CastListChunk;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.KeyTableChunk;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.id.ChunkId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

class CastMemberLookupTest {

    @Test
    void mapsCastListEntriesToCastMappingsByResourceIdBeforeMemberCount() {
        CastChunk physicalFirst = castMapping(501, 1001, 1002);
        CastChunk physicalSecond = castMapping(502, 2001, 2002);

        CastListChunk castList = new CastListChunk(null, new ChunkId(10), 0, 0, 2, List.of(
                castListEntry("Cast A", 9002),
                castListEntry("Cast B", 9001)));
        KeyTableChunk keyTable = keyTable(List.of(
                keyEntry(502, 9002, ChunkType.CASp),
                keyEntry(501, 9001, ChunkType.CASp)));

        CastMemberChunk member1001 = member(1001, "member_from_first_physical_mapping");
        CastMemberChunk member2001 = member(2001, "member_from_second_physical_mapping");
        Map<ChunkId, CastMemberChunk> membersById = Map.of(
                member1001.id(), member1001,
                member2001.id(), member2001);

        CastMemberLookup lookup = new CastMemberLookup(
                List.of(physicalFirst, physicalSecond),
                List.of(),
                castList,
                null,
                keyTable,
                membersById::get);

        assertSame(member2001, lookup.getByNumber(1, 1));
        assertSame(member1001, lookup.getByNumber(2, 1));
        assertSame(member2001, lookup.getByIndex(1, 0));
        assertSame(member1001, lookup.getByIndex(2, 0));
    }

    private static CastChunk castMapping(int resourceId, int... memberResourceIds) {
        List<Integer> memberIds = new ArrayList<>();
        for (int memberResourceId : memberResourceIds) {
            memberIds.add(memberResourceId);
        }
        return new CastChunk(null, new ChunkId(resourceId), memberIds);
    }

    private static CastListChunk.CastListEntry castListEntry(String name, int castResourceId) {
        return new CastListChunk.CastListEntry(name, "", 0, 1, 2, 2, castResourceId);
    }

    private static KeyTableChunk.KeyTableEntry keyEntry(int sectionId, int ownerId, ChunkType type) {
        return new KeyTableChunk.KeyTableEntry(new ChunkId(sectionId), new ChunkId(ownerId), type.getFourCC());
    }

    private static KeyTableChunk keyTable(List<KeyTableChunk.KeyTableEntry> entries) {
        Map<ChunkId, List<KeyTableChunk.KeyTableEntry>> byOwner = new HashMap<>();
        Map<ChunkId, ChunkId> ownerBySection = new HashMap<>();
        for (KeyTableChunk.KeyTableEntry entry : entries) {
            byOwner.computeIfAbsent(entry.castId(), ignored -> new ArrayList<>()).add(entry);
            ownerBySection.put(entry.sectionId(), entry.castId());
        }
        return new KeyTableChunk(null, new ChunkId(20), entries, byOwner, ownerBySection);
    }

    private static CastMemberChunk member(int resourceId, String name) {
        return new CastMemberChunk(
                null,
                new ChunkId(resourceId),
                MemberType.BITMAP,
                0,
                0,
                new byte[0],
                new byte[0],
                name,
                0,
                0,
                0);
    }
}
