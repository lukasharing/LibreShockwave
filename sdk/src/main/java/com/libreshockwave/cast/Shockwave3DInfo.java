package com.libreshockwave.cast;

import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

public record Shockwave3DInfo(
    String defaultShaderName,
    String worldName,
    String textureName,
    String cameraName,
    float drawDistance,
    float[] cameraPosition,
    float[] cameraTarget,
    int ambientR, int ambientG, int ambientB,
    int bgColorR, int bgColorG, int bgColorB,
    int[] headerFlags
) {

    public static boolean isShockwave3D(byte[] specificData) {
        return specificData != null && specificData.length >= 40;
    }

    public static Shockwave3DInfo parse(byte[] specificData) {
        if (specificData == null || specificData.length < 40) {
            return new Shockwave3DInfo(
                "", "", "", "", 0f,
                new float[3], new float[3],
                0, 0, 0, 0, 0, 0, new int[0]
            );
        }

        BinaryReader reader = new BinaryReader(specificData, ByteOrder.BIG_ENDIAN);

        // Header flags area — first 8 bytes
        int[] headerFlags = new int[2];
        headerFlags[0] = reader.readI32();
        headerFlags[1] = reader.readI32();

        // Draw distance
        float drawDistance = reader.readF32();

        // Camera position (3 floats)
        float[] cameraPosition = new float[3];
        cameraPosition[0] = reader.readF32();
        cameraPosition[1] = reader.readF32();
        cameraPosition[2] = reader.readF32();

        // Camera target (3 floats)
        float[] cameraTarget = new float[3];
        cameraTarget[0] = reader.readF32();
        cameraTarget[1] = reader.readF32();
        cameraTarget[2] = reader.readF32();

        // Ambient color (RGB as u8)
        int ambientR = reader.bytesLeft() >= 1 ? reader.readU8() : 0;
        int ambientG = reader.bytesLeft() >= 1 ? reader.readU8() : 0;
        int ambientB = reader.bytesLeft() >= 1 ? reader.readU8() : 0;

        // Background color (RGB as u8)
        int bgColorR = reader.bytesLeft() >= 1 ? reader.readU8() : 0;
        int bgColorG = reader.bytesLeft() >= 1 ? reader.readU8() : 0;
        int bgColorB = reader.bytesLeft() >= 1 ? reader.readU8() : 0;

        // String fields — Pascal-style u8 length + string
        String defaultShaderName = "";
        String worldName = "";
        String textureName = "";
        String cameraName = "";

        if (reader.bytesLeft() >= 1) {
            int len = reader.readU8();
            if (len > 0 && reader.bytesLeft() >= len) {
                defaultShaderName = reader.readString(len);
            }
        }
        if (reader.bytesLeft() >= 1) {
            int len = reader.readU8();
            if (len > 0 && reader.bytesLeft() >= len) {
                worldName = reader.readString(len);
            }
        }
        if (reader.bytesLeft() >= 1) {
            int len = reader.readU8();
            if (len > 0 && reader.bytesLeft() >= len) {
                textureName = reader.readString(len);
            }
        }
        if (reader.bytesLeft() >= 1) {
            int len = reader.readU8();
            if (len > 0 && reader.bytesLeft() >= len) {
                cameraName = reader.readString(len);
            }
        }

        return new Shockwave3DInfo(
            defaultShaderName, worldName, textureName, cameraName,
            drawDistance, cameraPosition, cameraTarget,
            ambientR, ambientG, ambientB,
            bgColorR, bgColorG, bgColorB,
            headerFlags
        );
    }
}
