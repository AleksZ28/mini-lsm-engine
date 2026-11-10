package com.ibm.internship.lsm.core;

import com.ibm.internship.lsm.api.StorageEngine;
import com.ibm.internship.lsm.memtable.MemTable;
import com.ibm.internship.lsm.sstable.SSTableReader;
import com.ibm.internship.lsm.sstable.SSTableWriter;
import com.ibm.internship.lsm.wal.OpType;
import com.ibm.internship.lsm.wal.Wal;
import com.ibm.internship.lsm.wal.WalDecoder;
import com.ibm.internship.lsm.compaction.Compactor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MiniLsmEngine implements StorageEngine {
    public static final long DEFAULT_THRESHOLD_BYTES = 32 * 1024 * 1024L;
    public static final double FLUSH_RATIO = 0.7;
    public static final int DEFAULT_COMPACTION_THRESHOLD = 4;

    private volatile MemTable activeMemTable;
    private volatile MemTable immutableMemTable;
    private final Compactor compactor = new Compactor();
    private final Object flushLock = new Object();
    private final Object walLock = new Object();
    private final Object compactionLock = new Object();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ExecutorService flushExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lsm-flush-worker");
        t.setDaemon(true);
        return t;
    });
    private volatile IOException backgroundFlushException;
    private final int compactionThreshold;
    private final ExecutorService compactionExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lsm-compaction-worker");
        t.setDaemon(true);
        return t;
    });
    private volatile IOException backgroundCompactionException;
    private volatile Wal wal;
    private final Path walPath;
    private final Path flushingWalPath;
    private final Path sstDir;
    private volatile List<SSTableReader> sstables;
    private final long thresholdBytes;
    private final long flushThresholdBytes;

    public MiniLsmEngine(Path walPath, Path sstDir) throws IOException {
        this(walPath, sstDir, DEFAULT_THRESHOLD_BYTES, DEFAULT_COMPACTION_THRESHOLD, loadSSTables(sstDir));
    }

    public MiniLsmEngine(Path walPath, Path sstDir, long thresholdBytes) throws IOException {
        this(walPath, sstDir, thresholdBytes, DEFAULT_COMPACTION_THRESHOLD, loadSSTables(sstDir));
    }

    public MiniLsmEngine(Path walPath, Path sstDir, List<SSTableReader> sstables) throws IOException {
        this(walPath, sstDir, DEFAULT_THRESHOLD_BYTES, DEFAULT_COMPACTION_THRESHOLD, sstables);
    }

    public MiniLsmEngine(Path walPath, Path sstDir, long thresholdBytes, List<SSTableReader> sstables) throws IOException {
        this(walPath, sstDir, thresholdBytes, DEFAULT_COMPACTION_THRESHOLD, sstables);
    }

    public MiniLsmEngine(Path walPath, Path sstDir, long thresholdBytes, int compactionThreshold, List<SSTableReader> sstables) throws IOException {
        if (thresholdBytes <= 0) {
            throw new IllegalArgumentException("thresholdBytes must be positive");
        }
        if (compactionThreshold < 2) {
            throw new IllegalArgumentException("compactionThreshold must be at least 2");
        }
        this.activeMemTable = new MemTable();
        this.walPath = Objects.requireNonNull(walPath, "walPath cannot be null");
        this.flushingWalPath = walPath.resolveSibling(walPath.getFileName().toString() + ".flushing");
        this.wal = new Wal(walPath);
        this.sstDir = normalizeDir(sstDir);
        if (!Files.exists(this.sstDir)) {
            Files.createDirectories(this.sstDir);
        }
        this.thresholdBytes = thresholdBytes;
        this.flushThresholdBytes = Math.max(1, (long) (thresholdBytes * FLUSH_RATIO));
        this.compactionThreshold = compactionThreshold;
        this.sstables = new CopyOnWriteArrayList<>(Objects.requireNonNull(sstables, "sstables cannot be null"));
    }

    @Override
    public void put(String key, byte[] value) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("key cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("value cannot be null");
        }

        checkBackgroundException();

        rwLock.readLock().lock();
        try {
            synchronized (walLock) {
                wal.append(key, value, false);
                activeMemTable.put(key, value);
            }
        } finally {
            rwLock.readLock().unlock();
        }

        if (activeMemTable.getBytesSize() >= flushThresholdBytes) {
            triggerBackgroundFlush();
        }
    }

    @Override
    public ByteBuffer get(String key) throws IOException {
        if (key == null) {
            return null;
        }

        MemTable.Entry memEntry = activeMemTable.getEntry(key);
        if (memEntry != null) {
            return memEntry.isTombstone() ? null : memEntry.getValue();
        }

        MemTable immutable = this.immutableMemTable;
        if (immutable != null) {
            MemTable.Entry immEntry = immutable.getEntry(key);
            if (immEntry != null) {
                return immEntry.isTombstone() ? null : immEntry.getValue();
            }
        }

        List<SSTableReader> currentSstables = this.sstables;
        for (SSTableReader sstable : currentSstables) {
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

        checkBackgroundException();

        rwLock.readLock().lock();
        try {
            synchronized (walLock) {
                wal.append(key, null, true);
                activeMemTable.delete(key);
            }
        } finally {
            rwLock.readLock().unlock();
        }

        if (activeMemTable.getBytesSize() >= flushThresholdBytes) {
            triggerBackgroundFlush();
        }
    }

    private void triggerBackgroundFlush() throws IOException {
        synchronized (flushLock) {
            while (immutableMemTable != null) {
                if (activeMemTable.getBytesSize() < flushThresholdBytes) {
                    return;
                }
                try {
                    flushLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for background flush", e);
                }
            }

            checkBackgroundException();

            if (activeMemTable.getBytesSize() < flushThresholdBytes || activeMemTable.isEmpty()) {
                return;
            }

            rotateMemTableAndWal();
            flushExecutor.submit(this::executeFlushTask);
        }
    }

    @Override
    public void flush() throws IOException {
        synchronized (flushLock) {
            waitForFlushLocked();

            if (activeMemTable.isEmpty()) {
                return;
            }

            rotateMemTableAndWal();
            flushExecutor.submit(this::executeFlushTask);
            waitForFlushLocked();
        }
    }

    public void waitForFlush() throws IOException {
        synchronized (flushLock) {
            waitForFlushLocked();
        }
    }

    private void waitForFlushLocked() throws IOException {
        while (immutableMemTable != null) {
            try {
                flushLock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for background flush", e);
            }
        }
        checkBackgroundException();
    }

    private void rotateMemTableAndWal() throws IOException {
        rwLock.writeLock().lock();
        try {
            immutableMemTable = activeMemTable;
            activeMemTable = new MemTable();

            wal.close();
            Files.move(walPath, flushingWalPath, StandardCopyOption.REPLACE_EXISTING);
            wal = new Wal(walPath);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void executeFlushTask() {
        try {
            MemTable tableToFlush = this.immutableMemTable;
            if (tableToFlush == null) {
                return;
            }

            long sstIndex = findHighestSstIndex() + 1;
            Path sstPath = sstDir.resolve(sstIndex + ".sst");

            try (SSTableWriter sstwriter = new SSTableWriter(sstPath)) {
                sstwriter.writeFromMemTable(tableToFlush);
                sstwriter.finish();
            }

            addSSTable(SSTableReader.open(sstPath));
            Files.deleteIfExists(flushingWalPath);

            synchronized (flushLock) {
                immutableMemTable = null;
                flushLock.notifyAll();
            }

            if (sstables.size() >= compactionThreshold) {
                triggerBackgroundCompaction();
            }
        } catch (IOException e) {
            synchronized (flushLock) {
                backgroundFlushException = e;
                immutableMemTable = null;
                flushLock.notifyAll();
            }
        }
    }

    private void triggerBackgroundCompaction() {
        if (sstables.size() < compactionThreshold) {
            return;
        }
        compactionExecutor.submit(this::executeCompactionTask);
    }

    private void executeCompactionTask() {
        try {
            compact();
        } catch (IOException e) {
            this.backgroundCompactionException = e;
        }
    }

    private void checkBackgroundException() throws IOException {
        IOException flushEx = this.backgroundFlushException;
        if (flushEx != null) {
            this.backgroundFlushException = null;
            throw flushEx;
        }
        IOException compactEx = this.backgroundCompactionException;
        if (compactEx != null) {
            this.backgroundCompactionException = null;
            throw compactEx;
        }
    }

    @Override
    public void recoverFromWal() throws IOException {
        rwLock.writeLock().lock();
        try {
            if (Files.exists(flushingWalPath)) {
                try (Wal oldWal = new Wal(flushingWalPath)) {
                    oldWal.recover(activeMemTable);
                }
            }
            wal.recover(activeMemTable);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void compact() throws IOException {
        synchronized (compactionLock) {
            waitForFlush();

            if(sstables.size() < 2) {
                return;
            }

            List<SSTableReader> toCompact = new ArrayList<>(sstables);

            long newIndex = findHighestSstIndex() + 1;
            Path compactedPath = sstDir.resolve(newIndex + ".sst");

            SSTableReader compactReader = compactor.compact(toCompact, compactedPath);

            rwLock.writeLock().lock();
            try {
                List<SSTableReader> updatedSstables = new ArrayList<>(this.sstables);
                updatedSstables.removeAll(toCompact);
                updatedSstables.add(compactReader);
                updatedSstables.sort((r1, r2) -> Long.compare(
                        parseSstIndex(r2.getPath()),
                        parseSstIndex(r1.getPath())
                ));
                this.sstables = new CopyOnWriteArrayList<>(updatedSstables);
            } finally {
                rwLock.writeLock().unlock();
            }

            for (SSTableReader oldReader : toCompact) {
                try {
                    oldReader.close();
                } catch (IOException ignored) {}
                Files.deleteIfExists(oldReader.getPath());
            }
        }
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
        rwLock.writeLock().lock();
        try {
            sstables.addFirst(sstable);
        } finally {
            rwLock.writeLock().unlock();
        }
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

    public long getMemTableSizeBytes() {
        MemTable immutable = this.immutableMemTable;
        return activeMemTable.getBytesSize() + (immutable != null ? immutable.getBytesSize() : 0);
    }

    public int getMemTableEntryCount() {
        MemTable immutable = this.immutableMemTable;
        return activeMemTable.allEntryCount() + (immutable != null ? immutable.allEntryCount() : 0);
    }
  
    public long getThresholdBytes() {
        return thresholdBytes;
    }

    public long getFlushThresholdBytes() {
        return flushThresholdBytes;
    }

    public int getCompactionThreshold() {
        return compactionThreshold;
    }

    public void waitForCompaction() throws IOException {
        if (compactionExecutor.isShutdown()) {
            return;
        }
        try {
            compactionExecutor.submit(() -> {}).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for background compaction", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException ioEx) {
                throw ioEx;
            }
            throw new IOException("Background compaction failed", e.getCause());
        }
        checkBackgroundException();
    }

    @Override
    public void close() throws IOException {
        IOException exception = null;
        try {
            waitForFlush();
        } catch (IOException e) {
            exception = e;
        }

        flushExecutor.shutdown();
        try {
            if (!flushExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                flushExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            flushExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        compactionExecutor.shutdown();
        try {
            if (!compactionExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                compactionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            compactionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        synchronized (compactionLock) {
            synchronized (flushLock) {
                rwLock.writeLock().lock();
                try {
                    try {
                        wal.close();
                    } catch (IOException e) {
                        if (exception == null) {
                            exception = e;
                        } else {
                            exception.addSuppressed(e);
                        }
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
                } finally {
                    rwLock.writeLock().unlock();
                }
            }
        }

        if (exception != null) {
            throw exception;
        }
    }
}
