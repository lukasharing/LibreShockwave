package com.libreshockwave.w3d;

import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

public record W3DShape(
    String name,
    String parentName,
    int flags,
    float[] transform,
    byte[] shapeData
) {

    public static W3DShape parse(byte[] data) {
        if (data == null || data.length < 4) {
            return new W3DShape("", "", 0, null, new byte[0]);
        }

        BinaryReader reader = new BinaryReader(data, ByteOrder.LITTLE_ENDIAN);

        String name = reader.readPString16();
        String parentName = reader.bytesLeft() >= 2 ? reader.readPString16() : "";
        int flags = reader.bytesLeft() >= 4 ? reader.readI32() : 0;

        float[] transform = null;
        if (reader.bytesLeft() >= 64) {
            transform = new float[16];
            for (int i = 0; i < 16; i++) {
                transform[i] = reader.readF32();
            }
        }

        byte[] shapeData = new byte[0];
        if (reader.bytesLeft() > 0) {
            shapeData = reader.readBytes(reader.bytesLeft());
        }

        return new W3DShape(name, parentName, flags, transform, shapeData);
    }
}
