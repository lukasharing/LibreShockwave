package com.libreshockwave.w3d;

public enum W3DEntryType {
    SCENE_ROOT(0x01),
    VERSION(0x02),
    BINARY_DATA(0x21),
    RESOURCE_REF(0x48),
    MATERIAL(0x49),
    LIGHT_DATA(0x71),
    NODE(0x72),
    MESH_RESOURCE(0x73),
    SHAPE(0x74),
    UNKNOWN(-1);

    private final int code;

    W3DEntryType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static W3DEntryType fromCode(int code) {
        for (W3DEntryType type : values()) {
            if (type.code == code) return type;
        }
        return UNKNOWN;
    }
}
