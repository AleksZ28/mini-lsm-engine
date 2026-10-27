package com.ibm.internship.lsm.core;

import com.ibm.internship.lsm.api.StorageEngine;
import com.ibm.internship.lsm.memtable.MemTable;
import com.ibm.internship.lsm.wal.Wal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class MiniLsmEngine implements StorageEngine {
    private final MemTable activeMemTable;
    private final Wal wal;

    public MiniLsmEngine(Path walPath) throws IOException {
        this.activeMemTable = new MemTable();
        this.wal = new Wal(walPath);
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
    public Optional<byte[]> get(String key) throws IOException {
        if (key == null) {
            return Optional.empty();
        }

        byte[] value = activeMemTable.get(key);
        return Optional.ofNullable(value);
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
        // TODO once flush is ready
    }

    @Override
    public void recoverFromWal() throws IOException {
        wal.recover(activeMemTable);
    }

    @Override
    public void close() throws IOException {
        wal.close();
    }
}
