package com.ibm.internship.lsm.sstable;

import com.ibm.internship.lsm.wal.OpType;

import java.nio.ByteBuffer;
import java.util.Objects;

public record SSTableEntry(String key, ByteBuffer value, OpType opType) {
    public SSTableEntry {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(opType, "opType cannot be null");
    }

    public boolean isTombstone() {
        return opType == OpType.DELETE;
    }
}
