package com.ibm.internship.lsm.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MiniLsmEngineTest {

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
}
