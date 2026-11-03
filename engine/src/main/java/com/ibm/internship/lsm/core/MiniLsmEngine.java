package com.ibm.internship.lsm.core;

import com.ibm.internship.lsm.api.StorageEngine;
import com.ibm.internship.lsm.memtable.MemTable;
import com.ibm.internship.lsm.sstable.SSTableReader;
import com.ibm.internship.lsm.wal.OpType;
import com.ibm.internship.lsm.wal.Wal;
import com.ibm.internship.lsm.wal.WalDecoder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class MiniLsmEngine implements StorageEngine {
    private final MemTable activeMemTable;
    private final Wal wal;
    private final List<SSTableReader> sstables;

    public MiniLsmEngine(Path walPath) throws IOException {
        this(walPath, Collections.emptyList());
    }

    public MiniLsmEngine(Path walPath, List<SSTableReader> sstables) throws IOException {
        this.activeMemTable = new MemTable();
        this.wal = new Wal(walPath);
        this.sstables = new ArrayList<>(Objects.requireNonNull(sstables, "sstables cannot be null"));
    }

    @Override
    public void put(String key, byte[] value) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("key cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("value cannot be null");
        }

        wal.append(key, value, false);
        activeMemTable.put(key, value);
    }

    @Override
    public ByteBuffer get(String key) throws IOException {
        if (key == null) {
            return null;
        }

        MemTable.Entry memEntry = activeMemTable.getEntry(key);
        if (memEntry != null) {
            if (memEntry.isTombstone()) {
                return null;
            }
            return memEntry.getValue();
        }

        for (SSTableReader sstable : sstables) {
            WalDecoder.DecodedRecord record = sstable.readRecord(key);
            if (record != null) {
                if (record.getOpType() == OpType.DELETE) {
                    return null;
                }
                return ByteBuffer.wrap(record.getValueBytes()).asReadOnlyBuffer();
            }
        }

        return null;
    }

    @Override
    public void delete(String key) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("key cannot be null");
        }

        wal.append(key, null, true);
        activeMemTable.delete(key);
    }

    @Override
    public void flush() throws IOException {
    }

    @Override
    public void recoverFromWal() throws IOException {
        wal.recover(activeMemTable);
    }

    public void addSSTable(SSTableReader sstable) {
        Objects.requireNonNull(sstable, "sstable cannot be null");
        sstables.add(0, sstable);
    }

    public List<SSTableReader> getSstables() {
        return Collections.unmodifiableList(sstables);
    }

    @Override
    public void close() throws IOException {
        IOException exception = null;
        try {
            wal.close();
        } catch (IOException e) {
            exception = e;
        }

        for (SSTableReader sstable : sstables) {
            try {
                sstable.close();
            } catch (IOException e) {
                if (exception == null) {
                    exception = e;
                } else {
                    exception.addSuppressed(e);
                }
            }
        }

        if (exception != null) {
            throw exception;
        }
    }
}
