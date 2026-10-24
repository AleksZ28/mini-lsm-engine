package com.ibm.internship.lsm.memtable;

import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

public class MemTable {
    private final TreeMap<String, Entry> map = new TreeMap<>();
    private final AtomicLong byteSize = new AtomicLong(0);

    public static final class Entry{
        private final byte[] value;
        private final boolean tombstone;

        private Entry(byte[] value, boolean tombstone) {
            this.value = value == null ? null : value.clone();
            this.tombstone = tombstone;
        }

        public static Entry value(byte[] value) {
            return new Entry(value, false);
        }

        public static Entry tombstone() {
            return new Entry(null,true);
        }

        public byte[] getValue() {
            return value == null ? null : value.clone();
        }

        public boolean isTombstone() {
            return tombstone;
        }
    }
}


