package com.libreshockwave.lookup;

import com.libreshockwave.chunks.CastChunk;
import com.libreshockwave.chunks.CastListChunk;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ConfigChunk;
import com.libreshockwave.chunks.KeyTableChunk;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.id.ChunkId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Provides cast member lookup functionality.
 * Encapsulates the logic for finding cast members by index or member number.
 */
public final class CastMemberLookup {

    private final List<CastChunk> casts;
    private final List<CastMemberChunk> castMembers;
    private final CastListChunk castList;
    private final ConfigChunk config;
    private final KeyTableChunk keyTable;
    private final Function<ChunkId, CastMemberChunk> castMemberById;

    // Maps castLib number (1+) to the correct CASp chunk
    private final Map<Integer, CastChunk> castLibToCASp;

    public CastMemberLookup(List<CastChunk> casts, List<CastMemberChunk> castMembers,
                            CastListChunk castList, ConfigChunk config) {
        this(casts, castMembers, castList, config, null, null);
    }

    public CastMemberLookup(List<CastChunk> casts, List<CastMemberChunk> castMembers,
                            CastListChunk castList, ConfigChunk config,
                            Function<ChunkId, CastMemberChunk> castMemberById) {
        this(casts, castMembers, castList, config, null, castMemberById);
    }

    public CastMemberLookup(List<CastChunk> casts, List<CastMemberChunk> castMembers,
                            CastListChunk castList, ConfigChunk config,
                            KeyTableChunk keyTable,
                            Function<ChunkId, CastMemberChunk> castMemberById) {
        this.casts = casts;
        this.castMembers = castMembers;
        this.castList = castList;
        this.config = config;
        this.keyTable = keyTable;
        this.castMemberById = castMemberById;
        this.castLibToCASp = buildCastLibMapping();
    }

    /**
     * Build mapping from castLib number to CASp chunk.
     * CASp chunks in Afterburner files may not be ordered by cast library.
     * Match them to MCsL entries through KEY* when the cast ResourceID is available,
     * falling back to the legacy member-count heuristic for older/incomplete inputs.
     */
    private Map<Integer, CastChunk> buildCastLibMapping() {
        Map<Integer, CastChunk> mapping = new HashMap<>();

        if (castList == null || castList.entries().isEmpty()) {
            // No MCsL: assume positional ordering
            for (int i = 0; i < casts.size(); i++) {
                mapping.put(i + 1, casts.get(i));
            }
            return mapping;
        }

        boolean[] assigned = new boolean[casts.size()];

        for (int libIdx = 0; libIdx < castList.entries().size(); libIdx++) {
            CastListChunk.CastListEntry entry = castList.entries().get(libIdx);
            int castLibNum = libIdx + 1;

            int resourceMappedIndex = findCastIndexByResourceId(entry.id());
            if (resourceMappedIndex >= 0 && !assigned[resourceMappedIndex]) {
                mapping.put(castLibNum, casts.get(resourceMappedIndex));
                assigned[resourceMappedIndex] = true;
                continue;
            }

            int expectedCount = entry.memberCount();
            for (int ci = 0; ci < casts.size(); ci++) {
                if (assigned[ci]) continue;
                CastChunk cast = casts.get(ci);
                if (cast.memberIds().size() == expectedCount) {
                    mapping.put(castLibNum, cast);
                    assigned[ci] = true;
                    break;
                }
            }
        }

        return mapping;
    }

    private int findCastIndexByResourceId(int castResourceId) {
        if (keyTable == null || castResourceId <= 0) {
            return -1;
        }
        KeyTableChunk.KeyTableEntry castMappingEntry =
                keyTable.findEntry(new ChunkId(castResourceId), ChunkType.CASp.getFourCC());
        if (castMappingEntry == null) {
            return -1;
        }
        ChunkId mappingResourceId = castMappingEntry.sectionId();
        for (int i = 0; i < casts.size(); i++) {
            CastChunk cast = casts.get(i);
            if (cast != null && mappingResourceId.equals(cast.id())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Get a cast member by its score index (castLib, castMemberIndex).
     * Handles the minMember offset from the cast list.
     * @param castLib Cast library (0 for internal, 1+ for external)
     * @param castMemberIndex 0-based cast member index from score
     * @return The cast member, or null if not found
     */
    public CastMemberChunk getByIndex(int castLib, int castMemberIndex) {
        if (castMemberIndex < 0) {
            return null;
        }
        int effectiveCastLib = castLib > 0 ? castLib : 1;
        return getByNumber(effectiveCastLib, castMemberIndex + getMinMember(effectiveCastLib));
    }

    /**
     * Get a cast member by its member number (from score behavior references).
     * The member number is the slot position as seen in Director's cast window.
     * This uses the CASp chunk to map member numbers to chunk IDs.
     * @param castLib Cast library (1+)
     * @param memberNumber The member number as stored in the score
     * @return The cast member, or null if not found
     */
    public CastMemberChunk getByNumber(int castLib, int memberNumber) {
        // Find the CASp chunk for this cast library using the mapping
        CastChunk cast = castLibToCASp.get(castLib);
        if (cast == null) {
            // Fallback: try positional ordering
            int libIndex = Math.max(0, castLib - 1);
            if (libIndex >= casts.size()) {
                return null;
            }
            cast = casts.get(libIndex);
        }
        if (cast == null) {
            return null;
        }

        // Get minMember offset
        int minMember = getMinMember(castLib);

        // Calculate the index into the cast's member ID array
        int arrayIndex = memberNumber - minMember;
        if (arrayIndex < 0 || arrayIndex >= cast.memberIds().size()) {
            return null;
        }

        // Get the chunk ID for this member slot
        int rawChunkId = cast.memberIds().get(arrayIndex);
        if (rawChunkId <= 0) {
            return null;  // Empty slot
        }

        return resolveByChunkId(new ChunkId(rawChunkId));
    }

    private CastMemberChunk resolveByChunkId(ChunkId chunkId) {
        if (chunkId == null) {
            return null;
        }
        if (castMemberById != null) {
            CastMemberChunk member = castMemberById.apply(chunkId);
            if (member != null) {
                return member;
            }
        }
        for (CastMemberChunk member : castMembers) {
            if (member.id().equals(chunkId)) {
                return member;
            }
        }
        return null;
    }

    /**
     * Get the minMember offset for a cast library.
     */
    private int getMinMember(int castLib) {
        int minMember = 1;
        if (castList != null && !castList.entries().isEmpty()) {
            int libIndex = Math.max(0, castLib - 1);
            if (libIndex < castList.entries().size()) {
                minMember = castList.entries().get(libIndex).minMember();
            }
        } else if (config != null) {
            // For .cct files without MCsL, use config's minMember
            minMember = config.minMember();
        }
        if (minMember <= 0) minMember = 1;
        return minMember;
    }
}
