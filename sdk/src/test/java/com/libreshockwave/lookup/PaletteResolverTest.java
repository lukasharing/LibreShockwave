package com.libreshockwave.lookup;

import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.KeyTableChunk;
import com.libreshockwave.chunks.PaletteChunk;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.io.BinaryReader;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaletteResolverTest {

    @Test
    void paletteZeroKeepsMemberOrdinalFallbackUnlessDefaultClutRequested() {
        PaletteChunk defaultClut = paletteChunk(300, 0x123456);
        PaletteChunk paletteMemberClut = paletteChunk(301, 0xABCDEF);
        CastMemberChunk paletteMember = new CastMemberChunk(
                null,
                new ChunkId(101),
                MemberType.PALETTE,
                0,
                0,
                new byte[0],
                new byte[0],
                "first_palette_member",
                0,
                0,
                0
        );
        KeyTableChunk.KeyTableEntry entry = new KeyTableChunk.KeyTableEntry(
                new ChunkId(301),
                new ChunkId(101),
                BinaryReader.fourCC("CLUT")
        );
        Map<ChunkId, List<KeyTableChunk.KeyTableEntry>> byOwner = new HashMap<>();
        byOwner.put(new ChunkId(101), List.of(entry));
        Map<ChunkId, ChunkId> bySection = new HashMap<>();
        bySection.put(new ChunkId(301), new ChunkId(101));
        KeyTableChunk keyTable = new KeyTableChunk(
                null,
                new ChunkId(400),
                List.of(entry),
                byOwner,
                bySection
        );

        PaletteResolver resolver = new PaletteResolver(
                List.of(),
                List.of(paletteMember),
                List.of(defaultClut, paletteMemberClut),
                null,
                null,
                keyTable,
                id -> id.value() == 301 ? paletteMemberClut : null
        );

        Palette memberResolved = resolver.resolve(0);
        Palette defaultResolved = resolver.resolveDefaultPaletteChunk(0);

        assertEquals(0xABCDEF, memberResolved.getColor(68));
        assertEquals(0x123456, defaultResolved.getColor(68));
    }

    @Test
    void compactPaletteZeroCanBeRequestedSeparatelyFromDefaultClut() {
        PaletteChunk defaultClut = paletteChunk(300, 0x123456);
        PaletteChunk compactClut = new PaletteChunk(null, new ChunkId(301), new int[] {
                0xFFFFFF, 0xEEEEEE, 0x222222, 0x333333,
                0xE7F700, 0xF7CE00, 0xC68C00, 0x735200,
                0xA57300, 0, 0, 0, 0, 0, 0, 0
        });
        PaletteResolver resolver = new PaletteResolver(
                List.of(),
                List.of(),
                List.of(defaultClut, compactClut),
                null,
                null,
                null,
                id -> null
        );

        Palette defaultResolved = resolver.resolveDefaultPaletteChunk(0);
        Palette compactResolved = resolver.resolveCompactPaletteChunk(0);

        assertEquals(0x123456, defaultResolved.getColor(68));
        assertEquals(0xE7F700, compactResolved.getColor(4));
    }

    private static PaletteChunk paletteChunk(int id, int color68) {
        int[] colors = new int[256];
        colors[0] = 0xFFFFFF;
        colors[68] = color68;
        colors[255] = 0x000000;
        return new PaletteChunk(null, new ChunkId(id), colors);
    }
}
