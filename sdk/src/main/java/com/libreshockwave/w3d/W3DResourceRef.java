package com.libreshockwave.w3d;

import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

public record W3DResourceRef(String name, int refType, byte[] refData) {

    public static W3DResourceRef parse(byte[] data) {
        if (data == null || data.length < 4) {
            return new W3DResourceRef("", 0, new byte[0]);
        }

        BinaryReader reader = new BinaryReader(data, ByteOrder.LITTLE_ENDIAN);

        String name = reader.readPString16();
        int refType = reader.bytesLeft() >= 4 ? reader.readI32() : 0;

        byte[] refData = new byte[0];
        if (reader.bytesLeft() > 0) {
            refData = reader.readBytes(reader.bytesLeft());
        }

        return new W3DResourceRef(name, refType, refData);
    }
}
