package com.ibm.internship.lsm.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MiniLsmEngineTest {

    @Test
    @DisplayName("Should put and retrieve value successfully as ByteBuffer")
    void shouldPutAndGet(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
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

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
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

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
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

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
            assertThrows(IllegalArgumentException.class, () -> engine.put(null, new byte[]{1}));
            assertThrows(IllegalArgumentException.class, () -> engine.put("k", null));
            assertThrows(IllegalArgumentException.class, () -> engine.delete(null));
        }
    }

    @Test
    @DisplayName("Should recover state from WAL file after engine restart")
    void shouldRecoverFromWalAfterRestart(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("restart_test.wal");

        byte[] alice = "Alice".getBytes(StandardCharsets.UTF_8);
        byte[] bob = "Bob".getBytes(StandardCharsets.UTF_8);

        // 1. First session: write data, delete one key, then close
        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
            engine.put("user:1", alice);
            engine.put("user:2", bob);
            engine.delete("user:1");
        }

        // 2. Second session: open new engine instance on the same WAL file and recover
        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
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
}
