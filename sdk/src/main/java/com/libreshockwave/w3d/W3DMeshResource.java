package com.libreshockwave.w3d;

import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

public record W3DMeshResource(
    String name,
    int vertexCount,
    int faceCount,
    byte[] geometryData
) {

    public static W3DMeshResource parse(byte[] data) {
        if (data == null || data.length < 4) {
            return new W3DMeshResource("", 0, 0, new byte[0]);
        }

        BinaryReader reader = new BinaryReader(data, ByteOrder.LITTLE_ENDIAN);

        String name = reader.readPString16();
        int vertexCount = reader.bytesLeft() >= 4 ? reader.readI32() : 0;
        int faceCount = reader.bytesLeft() >= 4 ? reader.readI32() : 0;

        byte[] geometryData = new byte[0];
        if (reader.bytesLeft() > 0) {
            geometryData = reader.readBytes(reader.bytesLeft());
        }

        return new W3DMeshResource(name, vertexCount, faceCount, geometryData);
    }
}
