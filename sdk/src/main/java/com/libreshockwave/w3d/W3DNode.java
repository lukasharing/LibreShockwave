package com.libreshockwave.w3d;

import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

public record W3DNode(
    String name,
    String parentName,
    int flags,
    float[] transform,
    String resourceName,
    String refName,
    String shaderName
) {

    public float posX() {
        return transform != null && transform.length >= 13 ? transform[12] : 0f;
    }

    public float posY() {
        return transform != null && transform.length >= 14 ? transform[13] : 0f;
    }

    public float posZ() {
        return transform != null && transform.length >= 15 ? transform[14] : 0f;
    }

    public static W3DNode parse(byte[] data) {
        if (data == null || data.length < 4) {
            return new W3DNode("", "", 0, null, "", "", "");
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

        String resourceName = reader.bytesLeft() >= 2 ? reader.readPString16() : "";
        String refName = reader.bytesLeft() >= 2 ? reader.readPString16() : "";
        String shaderName = reader.bytesLeft() >= 2 ? reader.readPString16() : "";

        return new W3DNode(name, parentName, flags, transform, resourceName, refName, shaderName);
    }
}
