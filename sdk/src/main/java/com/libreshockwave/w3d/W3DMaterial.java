package com.libreshockwave.w3d;

import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

public record W3DMaterial(String name, byte[] materialData) {

    public static W3DMaterial parse(byte[] data) {
        if (data == null || data.length < 4) {
            return new W3DMaterial("", new byte[0]);
        }

        BinaryReader reader = new BinaryReader(data, ByteOrder.LITTLE_ENDIAN);

        String name = reader.readPString16();

        byte[] materialData = new byte[0];
        if (reader.bytesLeft() > 0) {
            materialData = reader.readBytes(reader.bytesLeft());
        }

        return new W3DMaterial(name, materialData);
    }
}
