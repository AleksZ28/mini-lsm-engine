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

    public void put(String key, byte[] value) {
        if(key == null){
            throw new IllegalArgumentException("Key cannot be null");
        }
        putEntry(key, Entry.value(value));
    }

    private void putEntry(String key, Entry newEntry) {
        map.put(key, newEntry);
    }

    public byte[] get(String key){
        if(key == null){
            return null;
        }

        Entry entry = map.get(key);

        if(entry == null || entry.isTombstone()){
            return null;
        }

        return entry.getValue();
    }
}


