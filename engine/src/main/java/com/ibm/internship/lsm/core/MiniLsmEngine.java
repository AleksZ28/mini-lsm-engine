package com.ibm.internship.lsm.core;

import com.ibm.internship.lsm.api.StorageEngine;
import com.ibm.internship.lsm.memtable.MemTable;
import com.ibm.internship.lsm.sstable.SSTableReader;
import com.ibm.internship.lsm.sstable.SSTableWriter;
import com.ibm.internship.lsm.wal.OpType;
import com.ibm.internship.lsm.wal.Wal;
import com.ibm.internship.lsm.wal.WalDecoder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class MiniLsmEngine implements StorageEngine {
    private MemTable activeMemTable;
    private final Wal wal;
    private final Path sstDir;
    private final List<SSTableReader> sstables;

    public MiniLsmEngine(Path walPath, Path sstDir) throws IOException {
        this(walPath, sstDir, loadSSTables(sstDir));
    }

    public MiniLsmEngine(Path walPath, Path sstDir, List<SSTableReader> sstables) throws IOException {
        this.activeMemTable = new MemTable();
        this.wal = new Wal(walPath);
        this.sstDir = normalizeDir(sstDir);
        if (!Files.exists(this.sstDir)) {
            Files.createDirectories(this.sstDir);
        }
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

        addSSTable(SSTableReader.open(sstPath));
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
                long currentIndex = parseSstIndex(file);
                if (currentIndex > maxIndex) {
                    maxIndex = currentIndex;
                }
            }
        }

        return maxIndex;
    }

    public void addSSTable(SSTableReader sstable) {
        Objects.requireNonNull(sstable, "sstable cannot be null");
        sstables.addFirst(sstable);
    }

    public List<SSTableReader> getSstables() {
        return Collections.unmodifiableList(sstables);
    }

    private static List<SSTableReader> loadSSTables(Path sstDir) throws IOException {
        Path dir = normalizeDir(sstDir);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return Collections.emptyList();
        }

        List<Path> sstFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.sst")) {
            for (Path file : stream) {
                sstFiles.add(file);
            }
        }

        sstFiles.sort((p1, p2) -> Long.compare(parseSstIndex(p2), parseSstIndex(p1)));

        List<SSTableReader> readers = new ArrayList<>();
        try {
            for (Path file : sstFiles) {
                readers.add(SSTableReader.open(file));
            }
            return readers;
        } catch (IOException e) {
            for (SSTableReader reader : readers) {
                try {
                    reader.close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw e;
        }
    }

    private static long parseSstIndex(Path file) {
        String fileName = file.getFileName().toString();
        if (fileName.endsWith(".sst")) {
            try {
                return Long.parseLong(fileName.substring(0, fileName.length() - 4));
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private static Path normalizeDir(Path sstDir) {
        if (sstDir != null && sstDir.getFileName() != null && sstDir.getFileName().toString().endsWith(".sst")) {
            return sstDir.getParent() != null ? sstDir.getParent() : Path.of(".");
        }
        return sstDir != null ? sstDir : Path.of(".");
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
