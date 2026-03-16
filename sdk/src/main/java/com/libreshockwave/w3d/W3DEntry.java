package com.libreshockwave.w3d;

import com.libreshockwave.io.BinaryReader;

public record W3DEntry(W3DEntryType type, int parentRef, byte[] data) {

    public static W3DEntry read(BinaryReader reader) {
        int typeCode = reader.readU16();
        int dataLen = reader.readI32();
        int parentRef = reader.readI32();

        byte[] data = new byte[0];
        if (dataLen > 0 && reader.bytesLeft() >= dataLen) {
            data = reader.readBytes(dataLen);
        }

        return new W3DEntry(W3DEntryType.fromCode(typeCode), parentRef, data);
    }
}
