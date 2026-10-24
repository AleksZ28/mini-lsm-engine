package com.ibm.internship.lsm.memtable;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
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
        Entry oldEntry = map.put(key, newEntry);

        long oldSize = oldEntry == null ? 0 : calculateEntrySize(key, oldEntry);
        long newSize = calculateEntrySize(key,newEntry);

        byteSize.addAndGet(newSize - oldSize);
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

    public void delete(String key) {
        if(key == null){
            throw new IllegalArgumentException("Key cannot be null");
        }
        putEntry(key, Entry.tombstone());
    }

    private long calculateEntrySize(String key, Entry entry){
        long size = key.getBytes(StandardCharsets.UTF_8).length;

        if(!entry.isTombstone() && entry.value != null) {
            size += entry.value.length;
        }

        return size;
    }

    public int entryCount() {
        int count = 0;

        for(Entry entry : map.values()) {
            if(!entry.isTombstone()) {
                count++;
            }
        }

        return count;
    }

    public int allEntryCount() {
        return map.size();
    }

    public long getBytesSize() {
        return byteSize.get();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public Set<Map.Entry<String,Entry>> entrySet() {
        TreeMap<String, Entry> snapshot = new TreeMap<>();

        snapshot.putAll(map);

        return Collections.unmodifiableMap(snapshot).entrySet();
    }
}


