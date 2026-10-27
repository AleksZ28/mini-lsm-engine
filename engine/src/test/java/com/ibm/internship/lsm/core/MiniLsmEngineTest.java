package com.ibm.internship.lsm.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MiniLsmEngineTest {

    @Test
    @DisplayName("Should put and retrieve value successfully")
    void shouldPutAndGet(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
            byte[] value = "value123".getBytes(StandardCharsets.UTF_8);
            engine.put("user:1", value);

            Optional<byte[]> result = engine.get("user:1");
            assertTrue(result.isPresent(), "Value should be present");
            assertArrayEquals(value, result.get());
        }
    }

    @Test
    @DisplayName("Should return empty Optional for non-existent key")
    void shouldReturnEmptyForNonExistentKey(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
            Optional<byte[]> result = engine.get("missing:key");
            assertTrue(result.isEmpty(), "Non-existent key should return empty Optional");

            Optional<byte[]> nullResult = engine.get(null);
            assertTrue(nullResult.isEmpty(), "Null key should return empty Optional");
        }
    }

    @Test
    @DisplayName("Should delete key and make it unavailable")
    void shouldDeleteKey(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
            engine.put("user:1", "active".getBytes(StandardCharsets.UTF_8));
            assertTrue(engine.get("user:1").isPresent());

            engine.delete("user:1");
            assertTrue(engine.get("user:1").isEmpty(), "Deleted key should return empty Optional");
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

            assertTrue(engine.get("user:1").isEmpty(), "Deleted user:1 should remain deleted after recovery");
            Optional<byte[]> user2 = engine.get("user:2");
            assertTrue(user2.isPresent(), "user:2 should be restored from WAL");
            assertArrayEquals(bob, user2.get());

            // Verify engine can continue accepting new writes after recovery
            byte[] charlie = "Charlie".getBytes(StandardCharsets.UTF_8);
            engine.put("user:3", charlie);
            Optional<byte[]> user3 = engine.get("user:3");
            assertTrue(user3.isPresent(), "user:3 should be written");
            assertArrayEquals(charlie, user3.get());
        }
    }
}
