package com.ibm.internship.lsm.core;

import com.ibm.internship.lsm.sstable.IndexEntry;
import com.ibm.internship.lsm.sstable.SSTableFooter;
import com.ibm.internship.lsm.sstable.SSTableReader;
import com.ibm.internship.lsm.wal.WalEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MiniLsmEngineTest {

    record TestRecord(String key, byte[] value, boolean isDelete) {}

    private SSTableReader createSSTableReader(Path path, List<TestRecord> records) throws IOException {
        List<TestRecord> sortedRecords = new ArrayList<>(records);
        sortedRecords.sort(Comparator.comparing(TestRecord::key));

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long currentOffset = 0;
            List<IndexEntry> indexEntries = new ArrayList<>();

            for (TestRecord r : sortedRecords) {
                ByteBuffer recordBuf = WalEncoder.encode(r.key(), r.value(), r.isDelete());
                int recordLen = recordBuf.remaining();
                long offset = currentOffset;

                while (recordBuf.hasRemaining()) {
                    channel.write(recordBuf);
                }

                currentOffset += recordLen;
                indexEntries.add(new IndexEntry(r.key(), offset, recordLen));
            }

            long indexOffset = currentOffset;

            for (IndexEntry ie : indexEntries) {
                byte[] keyBytes = ie.key().getBytes(StandardCharsets.UTF_8);
                ByteBuffer buf = ByteBuffer.allocate(16 + keyBytes.length);
                buf.putInt(keyBytes.length);
                buf.put(keyBytes);
                buf.putLong(ie.offset());
                buf.putInt(ie.recordLength());
                buf.flip();

                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
            }

            int indexLength = (int) (channel.position() - indexOffset);
            ByteBuffer footer = ByteBuffer.allocate(SSTableFooter.FOOTER_SIZE);
            footer.putLong(indexOffset);
            footer.putInt(indexLength);
            footer.putInt(indexEntries.size());
            footer.putLong(SSTableFooter.MAGIC_NUMBER);
            footer.flip();

            while (footer.hasRemaining()) {
                channel.write(footer);
            }
        }

        return SSTableReader.open(path);
    }

    @Test
    @DisplayName("Should put and retrieve value successfully as ByteBuffer")
    void shouldPutAndGet(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");
        Path sstPath = tempDir.resolve("test.sst");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstPath)) {
            byte[] value = "value123".getBytes(StandardCharsets.UTF_8);
            engine.put("user:1", value);

            ByteBuffer result = engine.get("user:1");
            assertNotNull(result, "Value should be present");
            assertEquals(ByteBuffer.wrap(value), result);
        }
    }

    @Test
    @DisplayName("Should return null for non-existent key")
    void shouldReturnNullForNonExistentKey(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");
        Path sstPath = tempDir.resolve("test.sst");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstPath)) {
            ByteBuffer result = engine.get("missing:key");
            assertNull(result, "Non-existent key should return null");

            ByteBuffer nullResult = engine.get(null);
            assertNull(nullResult, "Null key should return null");
        }
    }

    @Test
    @DisplayName("Should delete key and make it unavailable")
    void shouldDeleteKey(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");
        Path sstPath = tempDir.resolve("test.sst");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstPath)) {
            engine.put("user:1", "active".getBytes(StandardCharsets.UTF_8));
            assertNotNull(engine.get("user:1"));

            engine.delete("user:1");
            assertNull(engine.get("user:1"), "Deleted key should return null");
        }
    }

    @Test
    @DisplayName("Should reject null keys and values with IllegalArgumentException")
    void shouldRejectNulls(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");
        Path sstPath = tempDir.resolve("test.sst");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstPath)) {
            assertThrows(IllegalArgumentException.class, () -> engine.put(null, new byte[]{1}));
            assertThrows(IllegalArgumentException.class, () -> engine.put("k", null));
            assertThrows(IllegalArgumentException.class, () -> engine.delete(null));
        }
    }

    @Test
    @DisplayName("Should recover state from WAL file after engine restart")
    void shouldRecoverFromWalAfterRestart(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("restart_test.wal");
        Path sstPath = tempDir.resolve("restart_test.sst");

        byte[] alice = "Alice".getBytes(StandardCharsets.UTF_8);
        byte[] bob = "Bob".getBytes(StandardCharsets.UTF_8);

        // 1. First session: write data, delete one key, then close
        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstPath)) {
            engine.put("user:1", alice);
            engine.put("user:2", bob);
            engine.delete("user:1");
        }

        // 2. Second session: open new engine instance on the same WAL file and recover
        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstPath)) {
            engine.recoverFromWal();

            assertNull(engine.get("user:1"), "Deleted user:1 should remain deleted after recovery");
            ByteBuffer user2 = engine.get("user:2");
            assertNotNull(user2, "user:2 should be restored from WAL");
            assertEquals(ByteBuffer.wrap(bob), user2);

            // Verify engine can continue accepting new writes after recovery
            byte[] charlie = "Charlie".getBytes(StandardCharsets.UTF_8);
            engine.put("user:3", charlie);
            ByteBuffer user3 = engine.get("user:3");
            assertNotNull(user3, "user:3 should be written");
            assertEquals(ByteBuffer.wrap(charlie), user3);
        }
    }

    @Test
    @DisplayName("Should flush active MemTable to SSTable file and reset WAL")
    void shouldFlushActiveMemTableToSSTableAndResetWal(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("flush_test.wal");
        Path sstDir = tempDir.resolve("sstables");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir)) {
            engine.put("k1", "val1".getBytes(StandardCharsets.UTF_8));
            engine.put("k2", "val2".getBytes(StandardCharsets.UTF_8));

            assertTrue(Files.size(walPath) > 0, "WAL must contain unpersisted writes before flush");

            engine.flush();

            // 1. Verify 1.sst was created and has data
            Path expectedSst = sstDir.resolve("1.sst");
            assertTrue(Files.exists(expectedSst), "SSTable 1.sst must exist after flush");
            assertTrue(Files.size(expectedSst) > 24, "SSTable file must contain data and footer");

            // 2. Verify WAL was reset to 0
            assertEquals(0, Files.size(walPath), "WAL must be truncated to 0 after successful flush");

            // 3. Verify activeMemTable was replaced and engine accepts new writes
            byte[] v3 = "val3".getBytes(StandardCharsets.UTF_8);
            engine.put("k3", v3);
            ByteBuffer res3 = engine.get("k3");
            assertNotNull(res3);
            assertEquals(ByteBuffer.wrap(v3), res3);
        }
    }

    @Test
    @DisplayName("Should do nothing when flushing an empty MemTable")
    void shouldNotCreateSSTableWhenMemTableIsEmpty(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("empty_flush.wal");
        Path sstDir = tempDir.resolve("sstables");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir)) {
            engine.flush();

            // No SSTable files should be created
            Path sst1 = sstDir.resolve("1.sst");
            assertFalse(Files.exists(sst1), "No SSTable should be created when MemTable is empty");
        }
    }

    @Test
    @DisplayName("Should increment SSTable file numbering on subsequent flushes")
    void shouldIncrementSSTableNumberOnSubsequentFlushes(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("multi_flush.wal");
        Path sstDir = tempDir.resolve("sstables");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir)) {
            // Flush 1
            engine.put("k1", "v1".getBytes(StandardCharsets.UTF_8));
            engine.flush();
            assertTrue(Files.exists(sstDir.resolve("1.sst")), "First flush should create 1.sst");

            // Flush 2
            engine.put("k2", "v2".getBytes(StandardCharsets.UTF_8));
            engine.flush();
            assertTrue(Files.exists(sstDir.resolve("2.sst")), "Second flush should create 2.sst");

            // Flush 3
            engine.put("k3", "v3".getBytes(StandardCharsets.UTF_8));
            engine.flush();
            assertTrue(Files.exists(sstDir.resolve("3.sst")), "Third flush should create 3.sst");
        }
    }

    @Test
    @DisplayName("Should continue numbering SSTables after engine restart")
    void shouldContinueNumberingAfterEngineRestart(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("restart_flush.wal");
        Path sstDir = tempDir.resolve("sstables");

        // First session: flush creates 1.sst
        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir)) {
            engine.put("a", "1".getBytes(StandardCharsets.UTF_8));
            engine.flush();
            assertTrue(Files.exists(sstDir.resolve("1.sst")));
        }

        // Second session: engine restarts and flush must create 2.sst, not overwrite 1.sst
        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir)) {
            engine.put("b", "2".getBytes(StandardCharsets.UTF_8));
            engine.flush();
            assertTrue(Files.exists(sstDir.resolve("1.sst")), "1.sst must still exist");
            assertTrue(Files.exists(sstDir.resolve("2.sst")), "Restarted engine flush should create 2.sst");
        }
    }

    @Test
    @DisplayName("Should retrieve value from SSTable when key is absent in MemTable")
    void shouldReadFromSSTableWhenNotInMemTable(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstPath = tempDir.resolve("00001.sst");
        byte[] sstValue = "sstable_val".getBytes(StandardCharsets.UTF_8);

        SSTableReader reader = createSSTableReader(sstPath, List.of(
                new TestRecord("disk:key", sstValue, false)
        ));

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, tempDir, List.of(reader))) {
            ByteBuffer result = engine.get("disk:key");
            assertNotNull(result);
            assertEquals(ByteBuffer.wrap(sstValue), result);

            assertNull(engine.get("non:existent"));
        }
    }

    @Test
    @DisplayName("Should prioritize MemTable updates over on-disk SSTable data")
    void shouldPrioritizeMemTableOverSSTable(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstPath = tempDir.resolve("00001.sst");
        byte[] oldVal = "old_value".getBytes(StandardCharsets.UTF_8);
        byte[] newVal = "new_value".getBytes(StandardCharsets.UTF_8);

        SSTableReader reader = createSSTableReader(sstPath, List.of(
                new TestRecord("user:1", oldVal, false)
        ));

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, tempDir, List.of(reader))) {
            assertEquals(ByteBuffer.wrap(oldVal), engine.get("user:1"));

            engine.put("user:1", newVal);
            assertEquals(ByteBuffer.wrap(newVal), engine.get("user:1"));
        }
    }

    @Test
    @DisplayName("Should respect MemTable tombstone and not resurrect older SSTable value")
    void shouldRespectMemTableTombstone(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstPath = tempDir.resolve("00001.sst");
        byte[] oldVal = "persisted_value".getBytes(StandardCharsets.UTF_8);

        SSTableReader reader = createSSTableReader(sstPath, List.of(
                new TestRecord("user:1", oldVal, false)
        ));

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, tempDir, List.of(reader))) {
            assertEquals(ByteBuffer.wrap(oldVal), engine.get("user:1"));

            engine.delete("user:1");
            assertNull(engine.get("user:1"));
        }
    }

    @Test
    @DisplayName("Should search SSTables from newest to oldest and respect SSTable tombstones")
    void shouldSearchMultipleSSTablesInOrder(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstOld = tempDir.resolve("00001.sst");
        Path sstNew = tempDir.resolve("00002.sst");

        byte[] k1Old = "k1_v1".getBytes(StandardCharsets.UTF_8);
        byte[] k1New = "k1_v2".getBytes(StandardCharsets.UTF_8);
        byte[] k2Val = "k2_live".getBytes(StandardCharsets.UTF_8);

        SSTableReader oldReader = createSSTableReader(sstOld, List.of(
                new TestRecord("k1", k1Old, false),
                new TestRecord("k2", k2Val, false)
        ));

        SSTableReader newReader = createSSTableReader(sstNew, List.of(
                new TestRecord("k1", k1New, false),
                new TestRecord("k2", null, true)
        ));

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, tempDir, new ArrayList<>())) {
            engine.addSSTable(oldReader);
            engine.addSSTable(newReader);

            assertEquals(2, engine.getSstables().size());
            assertEquals(ByteBuffer.wrap(k1New), engine.get("k1"));
            assertNull(engine.get("k2"));
        }
    }

    @Test
    @DisplayName("Should close all underlying SSTableReaders when engine closes")
    void shouldCloseSSTableReadersOnEngineClose(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstPath = tempDir.resolve("00001.sst");

        SSTableReader reader = createSSTableReader(sstPath, List.of(
                new TestRecord("k1", "v1".getBytes(StandardCharsets.UTF_8), false)
        ));

        MiniLsmEngine engine = new MiniLsmEngine(walPath, tempDir, List.of(reader));
        assertNotNull(engine.get("k1"));

        engine.close();

        assertThrows(IOException.class, () -> reader.get("k1"));
    }

    @Test
    @DisplayName("Should use default threshold bytes when not explicitly specified")
    void shouldUseDefaultThresholdWhenNotSpecified(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstDir = tempDir.resolve("sstables");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir)) {
            assertEquals(MiniLsmEngine.DEFAULT_THRESHOLD_BYTES, engine.getThresholdBytes());
        }
    }

    @Test
    @DisplayName("Should reject non-positive threshold bytes with IllegalArgumentException")
    void shouldRejectNonPositiveThresholdBytes(@TempDir Path tempDir) {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstDir = tempDir.resolve("sstables");

        assertThrows(IllegalArgumentException.class, () -> new MiniLsmEngine(walPath, sstDir, 0));
        assertThrows(IllegalArgumentException.class, () -> new MiniLsmEngine(walPath, sstDir, -100));
        assertThrows(IllegalArgumentException.class, () -> new MiniLsmEngine(walPath, sstDir, 0, List.of()));
    }

    @Test
    @DisplayName("Should not trigger flush when writes are below threshold")
    void shouldNotFlushWhenWritesAreBelowThreshold(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstDir = tempDir.resolve("sstables");
        long threshold = 100; // 100 bytes

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir, threshold)) {
            // Write 10 bytes ("k1" = 2 bytes, "value1234" = 9 bytes -> 11 bytes)
            engine.put("k1", "value1234".getBytes(StandardCharsets.UTF_8));

            assertTrue(engine.getSstables().isEmpty(), "No SSTables should be created when below threshold");
            assertTrue(Files.size(walPath) > 0, "WAL must contain unpersisted write");
            assertEquals(ByteBuffer.wrap("value1234".getBytes(StandardCharsets.UTF_8)), engine.get("k1"));
        }
    }

    @Test
    @DisplayName("Should automatically trigger flush when MemTable byte size reaches 70% threshold")
    void shouldAutomaticallyFlushWhenMemTableReachesThreshold(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstDir = tempDir.resolve("sstables");
        long threshold = 20; // 20 bytes threshold -> 70% = 14 bytes

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir, threshold)) {
            assertEquals(14, engine.getFlushThresholdBytes());

            // Write 1: "key1" (4) + "val1" (4) = 8 bytes (< 14, no flush)
            engine.put("key1", "val1".getBytes(StandardCharsets.UTF_8));
            assertTrue(engine.getSstables().isEmpty());
            assertTrue(Files.size(walPath) > 0);

            // Write 2: "key2" (4) + "val2" (4) = 8 bytes (total 16 >= 14 -> AUTOMATIC FLUSH!)
            engine.put("key2", "val2".getBytes(StandardCharsets.UTF_8));

            // Immediately after put(), WAL is rotated to 0 and data is readable from immutableMemTable
            assertEquals(0, Files.size(walPath), "Active WAL must be reset to 0 immediately upon rotation");
            assertEquals(ByteBuffer.wrap("val1".getBytes(StandardCharsets.UTF_8)), engine.get("key1"));
            assertEquals(ByteBuffer.wrap("val2".getBytes(StandardCharsets.UTF_8)), engine.get("key2"));

            // Wait for background flush worker to finish writing 1.sst
            engine.waitForFlush();
            assertEquals(1, engine.getSstables().size(), "One SSTable should be created automatically");
            assertTrue(Files.exists(sstDir.resolve("1.sst")), "1.sst must exist");

            // Write 3: accepts new writes into the refreshed MemTable
            engine.put("key3", "val3".getBytes(StandardCharsets.UTF_8));

            // Verify all data is intact and accessible
            assertEquals(ByteBuffer.wrap("val1".getBytes(StandardCharsets.UTF_8)), engine.get("key1"));
            assertEquals(ByteBuffer.wrap("val2".getBytes(StandardCharsets.UTF_8)), engine.get("key2"));
            assertEquals(ByteBuffer.wrap("val3".getBytes(StandardCharsets.UTF_8)), engine.get("key3"));
        }
    }

    @Test
    @DisplayName("Should automatically trigger multiple flushes as threshold is repeatedly exceeded")
    void shouldAutomaticallyFlushMultipleTimes(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstDir = tempDir.resolve("sstables");
        long threshold = 20; // 20 bytes -> 70% = 14 bytes

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir, threshold)) {
            // First batch: 3 writes * 6 bytes = 18 bytes (>= 14 -> flush 1)
            engine.put("a1", "val1".getBytes(StandardCharsets.UTF_8)); // 6 bytes (< 14)
            engine.put("a2", "val2".getBytes(StandardCharsets.UTF_8)); // 12 bytes (< 14)
            engine.put("a3", "val3".getBytes(StandardCharsets.UTF_8)); // 18 bytes (>= 14 -> flush 1)
            engine.waitForFlush();
            assertEquals(1, engine.getSstables().size());
            assertTrue(Files.exists(sstDir.resolve("1.sst")));

            // Second batch: 3 writes * 6 bytes = 18 bytes (>= 14 -> flush 2)
            engine.put("b1", "val1".getBytes(StandardCharsets.UTF_8)); // 6 bytes
            engine.put("b2", "val2".getBytes(StandardCharsets.UTF_8)); // 12 bytes
            engine.put("b3", "val3".getBytes(StandardCharsets.UTF_8)); // 18 bytes (>= 14 -> flush 2)
            engine.waitForFlush();
            assertEquals(2, engine.getSstables().size());
            assertTrue(Files.exists(sstDir.resolve("2.sst")));

            // Verify all keys from both flushes are readable
            assertEquals(ByteBuffer.wrap("val1".getBytes(StandardCharsets.UTF_8)), engine.get("a1"));
            assertEquals(ByteBuffer.wrap("val3".getBytes(StandardCharsets.UTF_8)), engine.get("b3"));
        }
    }

    @Test
    @DisplayName("Should automatically flush on delete when tombstone causes MemTable to reach threshold")
    void shouldAutomaticallyFlushOnDeleteTombstone(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstDir = tempDir.resolve("sstables");
        long threshold = 25; // 25 bytes -> 70% = 17 bytes

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir, threshold)) {
            engine.put("k1", "v1".getBytes(StandardCharsets.UTF_8)); // 4 bytes (< 17)

            // Delete key with a long name (30 bytes key size -> total 34 >= 17 -> triggers flush)
            String longDeletedKey = "long_key_name_to_trigger_flush";
            engine.delete(longDeletedKey);
            engine.waitForFlush();

            assertEquals(1, engine.getSstables().size(), "Delete should trigger automatic flush when threshold exceeded");
            assertNull(engine.get(longDeletedKey));
            assertEquals(ByteBuffer.wrap("v1".getBytes(StandardCharsets.UTF_8)), engine.get("k1"));
        }
    }

    @Test
    @DisplayName("Should read persisted data from SSTables after engine restart without WAL recovery")
    void shouldLoadSSTablesOnEngineRestart(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("restart_sst.wal");
        Path sstDir = tempDir.resolve("sstables");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir)) {
            engine.put("persisted_key", "persisted_value".getBytes(StandardCharsets.UTF_8));
            engine.flush();
        }

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir)) {
            assertEquals(1, engine.getSstables().size(), "Engine should load 1.sst");

            // we don't call recoverFromWal()
            ByteBuffer val = engine.get("persisted_key");
            assertNotNull(val, "Key should be present after restart");
            assertEquals(ByteBuffer.wrap("persisted_value".getBytes(StandardCharsets.UTF_8)), val);
        }
    }

    @Test
    @DisplayName("Should handle concurrent puts and gets across multiple threads")
    void shouldHandleConcurrentPutsAndGets(@TempDir Path tempDir) throws Exception {
        Path walPath = tempDir.resolve("concurrent.wal");
        Path sstDir = tempDir.resolve("sstables");

        int threadCount = 8;
        int operationsPerThread = 50;

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir)) {
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < operationsPerThread; i++) {
                            String key = "key_" + threadId + "_" + i;
                            byte[] val = ("val_" + threadId + "_" + i).getBytes(StandardCharsets.UTF_8);
                            engine.put(key, val);

                            ByteBuffer retrieved = engine.get(key);
                            assertNotNull(retrieved, "Value should be immediately readable after put");
                            assertEquals(ByteBuffer.wrap(val), retrieved);
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Threads should finish within timeout");
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            assertNull(failure.get(), "No thread should have encountered an exception");

            for (int t = 0; t < threadCount; t++) {
                for (int i = 0; i < operationsPerThread; i++) {
                    String key = "key_" + t + "_" + i;
                    byte[] expected = ("val_" + t + "_" + i).getBytes(StandardCharsets.UTF_8);
                    ByteBuffer val = engine.get(key);
                    assertNotNull(val, "Key should be present: " + key);
                    assertEquals(ByteBuffer.wrap(expected), val);
                }
            }
        }
    }

    @Test
    @DisplayName("Should handle concurrent writes triggering automatic flushes")
    void shouldHandleConcurrentWritesWithAutomaticFlushes(@TempDir Path tempDir) throws Exception {
        Path walPath = tempDir.resolve("concurrent_flush.wal");
        Path sstDir = tempDir.resolve("sstables");
        long threshold = 100;

        int threadCount = 6;
        int operationsPerThread = 40;

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir, threshold)) {
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < operationsPerThread; i++) {
                            String key = "flush_k_" + threadId + "_" + i;
                            byte[] val = ("flush_v_" + threadId + "_" + i).getBytes(StandardCharsets.UTF_8);
                            engine.put(key, val);
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(15, TimeUnit.SECONDS), "Threads should finish within timeout");
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            assertNull(failure.get(), "No thread should have encountered an exception during concurrent flushes");
            engine.waitForFlush();
            assertFalse(engine.getSstables().isEmpty(), "Flushes should have produced SSTables");

            for (int t = 0; t < threadCount; t++) {
                for (int i = 0; i < operationsPerThread; i++) {
                    String key = "flush_k_" + t + "_" + i;
                    byte[] expected = ("flush_v_" + t + "_" + i).getBytes(StandardCharsets.UTF_8);
                    ByteBuffer val = engine.get(key);
                    assertNotNull(val, "Key must be readable after flushes: " + key);
                    assertEquals(ByteBuffer.wrap(expected), val);
                }
            }
        }

        // Restart engine and recover from WAL to verify no data loss occurred
        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir)) {
            engine.recoverFromWal();
            for (int t = 0; t < threadCount; t++) {
                for (int i = 0; i < operationsPerThread; i++) {
                    String key = "flush_k_" + t + "_" + i;
                    byte[] expected = ("flush_v_" + t + "_" + i).getBytes(StandardCharsets.UTF_8);
                    ByteBuffer val = engine.get(key);
                    assertNotNull(val, "Key must survive engine restart and recovery: " + key);
                    assertEquals(ByteBuffer.wrap(expected), val);
                }
            }
        }
    }

    @Test
    @DisplayName("Should recover entries from both flushing WAL and active WAL after mid-flush crash")
    void shouldRecoverFromFlushingWalAndActiveWal(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path flushingWalPath = tempDir.resolve("engine.wal.flushing");
        Path sstDir = tempDir.resolve("sstables");

        // Simulate a WAL that was rotated to .flushing before a crash
        try (com.ibm.internship.lsm.wal.Wal flushingWal = new com.ibm.internship.lsm.wal.Wal(flushingWalPath)) {
            flushingWal.append("k1", "old_val".getBytes(StandardCharsets.UTF_8), false);
            flushingWal.append("k2", "flushing_val".getBytes(StandardCharsets.UTF_8), false);
        }

        // Simulate newer writes in the active WAL that arrived after rotation
        try (com.ibm.internship.lsm.wal.Wal activeWal = new com.ibm.internship.lsm.wal.Wal(walPath)) {
            activeWal.append("k1", "updated_val".getBytes(StandardCharsets.UTF_8), false);
            activeWal.append("k3", "active_val".getBytes(StandardCharsets.UTF_8), false);
        }

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir)) {
            engine.recoverFromWal();

            assertEquals(ByteBuffer.wrap("updated_val".getBytes(StandardCharsets.UTF_8)), engine.get("k1"),
                    "Active WAL entry must override older flushing WAL entry");
            assertEquals(ByteBuffer.wrap("flushing_val".getBytes(StandardCharsets.UTF_8)), engine.get("k2"),
                    "Flushing WAL entry must be recovered");
            assertEquals(ByteBuffer.wrap("active_val".getBytes(StandardCharsets.UTF_8)), engine.get("k3"),
                    "Active WAL entry must be recovered");
        }
    }
}
