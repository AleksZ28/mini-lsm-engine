package com.ibm.internship.lsm.core;

import com.ibm.internship.lsm.api.StorageEngine;
import com.ibm.internship.lsm.memtable.MemTable;
import com.ibm.internship.lsm.sstable.SSTableWriter;
import com.ibm.internship.lsm.wal.Wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class MiniLsmEngine implements StorageEngine {
    private MemTable activeMemTable;
    private final Wal wal;
    private final Path sstDir;

    public MiniLsmEngine(Path walPath, Path sstDir) throws IOException {
        this.activeMemTable = new MemTable();
        this.wal = new Wal(walPath);
        if (sstDir != null && sstDir.getFileName() != null && sstDir.getFileName().toString().endsWith(".sst")) {
            this.sstDir = sstDir.getParent() != null ? sstDir.getParent() : Path.of(".");
        } else {
            this.sstDir = sstDir != null ? sstDir : Path.of(".");
        }
        if (!Files.exists(this.sstDir)) {
            Files.createDirectories(this.sstDir);
        }
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

        return activeMemTable.get(key);
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
        if (activeMemTable.isEmpty()) {
            return;
        }

        MemTable memTableToFlush = activeMemTable;
        activeMemTable = new MemTable();

        long sstIndex = findHighestSstIndex() + 1;

        Path sstPath = sstDir.resolve(sstIndex + ".sst");

        try (SSTableWriter sstwriter = new SSTableWriter(sstPath)) {
            sstwriter.writeFromMemTable(memTableToFlush);
            sstwriter.finish();
            wal.reset();
        }
    }

    @Override
    public void recoverFromWal() throws IOException {
        wal.recover(activeMemTable);
    }

    private long findHighestSstIndex() throws IOException {
        if (!Files.exists(sstDir) || !Files.isDirectory(sstDir)) {
            return 0;
        }

        long maxIndex = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sstDir, "*.sst")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                String nameWithoutExtension = fileName.substring(0, fileName.length() - 4);

                try {
                    long currentIndex = Long.parseLong(nameWithoutExtension);
                    if (currentIndex > maxIndex) {
                        maxIndex = currentIndex;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        return maxIndex;
    }

    @Override
    public void close() throws IOException {
        wal.close();
    }
}
