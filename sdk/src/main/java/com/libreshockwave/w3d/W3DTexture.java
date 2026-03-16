package com.libreshockwave.w3d;

import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

public record W3DTexture(String name, byte[] imageData, String format) {

    public static W3DTexture parse(byte[] data) {
        if (data == null || data.length < 4) {
            return new W3DTexture("", new byte[0], "raw");
        }

        BinaryReader reader = new BinaryReader(data, ByteOrder.LITTLE_ENDIAN);

        String name = reader.readPString16();
        // Skip 1-byte type flag
        if (reader.bytesLeft() >= 1) {
            reader.skip(1);
        }

        byte[] imageData = new byte[0];
        if (reader.bytesLeft() > 0) {
            imageData = reader.readBytes(reader.bytesLeft());
        }

        String format = "raw";
        if (imageData.length >= 2
                && (imageData[0] & 0xFF) == 0xFF
                && (imageData[1] & 0xFF) == 0xD8) {
            format = "jpeg";
        }

        return new W3DTexture(name, imageData, format);
    }
}
