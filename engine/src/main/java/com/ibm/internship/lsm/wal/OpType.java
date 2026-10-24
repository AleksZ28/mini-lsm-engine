package com.ibm.internship.lsm.wal;

public enum OpType {
    PUT((byte) 0x01),
    DELETE((byte) 0x02);

    private final byte code;

    OpType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static OpType from(boolean isDelete) {
        return isDelete ? DELETE : PUT;
    }

    public static OpType fromCode(byte code) {
        for (OpType opType : values()) {
            if (opType.code == code) {
                return opType;
            }
        }
        throw new IllegalArgumentException("No OpType with code " + code);
    }

}
