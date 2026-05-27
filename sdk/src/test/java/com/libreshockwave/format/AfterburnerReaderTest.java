package com.libreshockwave.format;

import com.libreshockwave.io.BinaryReader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfterburnerReaderTest {

    @Test
    void equalSizeZlibChunksAreDecodedWhenTheyInflateToTheDeclaredSize() throws Exception {
        byte[] compressedStxt = hex(
                "78 da 63 60 60 e0 61 60 60 10 04 62 b1 68 a5 64 25 2b 85 68 "
                        + "e5 cc bc 6c 2b 05 63 e3 d8 58 06 46 a0 30 83 fc 07 86 f6 "
                        + "06 36 06 88 42 20 00 00 b3 4c 06 f3");

        byte[] decoded = decode(new ChunkInfo(
                4731, "STXT", 0, compressedStxt.length, compressedStxt.length, MoaID.ZLIB_COMPRESSION),
                compressedStxt);

        assertEquals(compressedStxt.length, decoded.length);
        assertEquals(0, decoded[0]);
        assertTrue(new String(decoded, java.nio.charset.StandardCharsets.ISO_8859_1).contains("[\"c\": [#ink: 33]]"));
    }

    @Test
    void equalSizeZlibLookingChunksStayRawWhenInflateIsIncomplete() throws Exception {
        byte[] raw = new byte[51];
        raw[0] = 0x78;
        raw[1] = (byte) 0xDA;
        for (int i = 2; i < raw.length; i++) {
            raw[i] = (byte) (0x30 + (i % 40));
        }

        byte[] decoded = decode(new ChunkInfo(
                99, "BITD", 0, raw.length, raw.length, MoaID.ZLIB_COMPRESSION),
                raw);

        assertArrayEquals(raw, decoded);
    }

    private static byte[] decode(ChunkInfo info, byte[] data) throws Exception {
        AfterburnerReader reader = new AfterburnerReader(
                new BinaryReader(new byte[0], ByteOrder.LITTLE_ENDIAN),
                ByteOrder.LITTLE_ENDIAN);
        Method method = AfterburnerReader.class.getDeclaredMethod("decodeChunkData", ChunkInfo.class, byte[].class);
        method.setAccessible(true);
        return (byte[]) method.invoke(reader, info, data);
    }

    private static byte[] hex(String input) {
        String normalized = input.replaceAll("\\s+", "");
        byte[] out = new byte[normalized.length() / 2];
        for (int i = 0; i < normalized.length(); i += 2) {
            out[i / 2] = (byte) Integer.parseInt(normalized.substring(i, i + 2), 16);
        }
        return out;
    }
}
