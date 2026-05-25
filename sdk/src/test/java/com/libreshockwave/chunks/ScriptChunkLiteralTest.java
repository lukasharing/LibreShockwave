package com.libreshockwave.chunks;

import com.libreshockwave.io.BinaryReader;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptChunkLiteralTest {

    @Test
    void readsDoubleWidthFloatLiterals() {
        ScriptChunk chunk = ScriptChunk.read(null, new BinaryReader(scriptWithFloatLiteral(8, 0.95)), null, 0x4B1, true);

        assertEquals(1, chunk.literals().size());
        assertEquals(0.95, chunk.literals().get(0).numericValue(), 0.000000000000001);
    }

    @Test
    void stillReadsFloatWidthFloatLiterals() {
        ScriptChunk chunk = ScriptChunk.read(null, new BinaryReader(scriptWithFloatLiteral(4, 0.95)), null, 0x4B1, true);

        assertEquals(1, chunk.literals().size());
        assertEquals(0.95f, chunk.literals().get(0).numericValue(), 0.000001);
    }

    private static byte[] scriptWithFloatLiteral(int dataLen, double value) {
        byte[] raw = new byte[128];
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(8, raw.length);
        buffer.putInt(12, raw.length);
        buffer.putShort(16, (short) 0);
        buffer.putShort(18, (short) 7);
        buffer.putInt(38, 7);

        buffer.putShort(78, (short) 1);
        buffer.putInt(80, 96);
        buffer.putInt(84, dataLen + 4);
        buffer.putInt(88, 112);

        buffer.putInt(96, 9);
        buffer.putInt(100, 0);

        buffer.putInt(112, dataLen);
        if (dataLen == Double.BYTES) {
            buffer.putDouble(116, value);
        } else {
            buffer.putFloat(116, (float) value);
        }

        return raw;
    }
}
