package com.ibm.internship.lsm.sstable;

import java.util.Objects;

public record IndexEntry(String key, long offset, int recordLength) implements Comparable<IndexEntry> {
    public static final int MIN_RECORD_LENGTH = 13; // CRC32 (4B) + OpType (1B) + KeyLen (4B) + ValLen (4B)

    public IndexEntry {
        Objects.requireNonNull(key, "key cannot be null");
        if (offset < 0) {
            throw new IllegalArgumentException("offset cannot be negative: " + offset);
        }
        if (recordLength < MIN_RECORD_LENGTH) {
            throw new IllegalArgumentException("recordLength must be at least " + MIN_RECORD_LENGTH + " bytes, but was: " + recordLength);
        }
    }

    @Override
    public int compareTo(IndexEntry other) {
        return this.key.compareTo(other.key);
    }
}
