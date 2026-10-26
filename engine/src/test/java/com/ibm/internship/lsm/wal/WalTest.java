package com.ibm.internship.lsm.wal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WalTest {

    @Test
    @DisplayName("Should write entries to file and track size")
    void shouldAppendAndTrackSize(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");
        try (Wal wal = new Wal(walPath)) {
            assertEquals(0, wal.size());
            wal.append("user:1", "value1".getBytes(StandardCharsets.UTF_8), false);
            assertTrue(wal.size() > 0);

            long sizeBeforeDelete = wal.size();
            wal.append("user:1", null, true);
            assertTrue(wal.size() > sizeBeforeDelete);
        }
    }

    @Test
    @DisplayName("Should reset wal to zero size")
    void shouldReset(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");
        try (Wal wal = new Wal(walPath)) {
            wal.append("key", "value".getBytes(StandardCharsets.UTF_8), false);
            assertTrue(wal.size() > 0);

            wal.reset();
            assertEquals(0, wal.size());
            assertEquals(0, Files.size(walPath));
        }
    }

    @Test
    @DisplayName("Should continue appending to existing WAL when reopened")
    void shouldAppendToExistingWalWhenReopened(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("reopen.wal");

        long firstSize;
        try (Wal wal = new Wal(walPath)) {
            wal.append("k1", "v1".getBytes(StandardCharsets.UTF_8), false);
            firstSize = wal.size();
            assertTrue(firstSize > 0);
        }

        try (Wal wal = new Wal(walPath)) {
            assertEquals(firstSize, wal.size(), "Reopened WAL should retain previous size");
            wal.append("k2", "v2".getBytes(StandardCharsets.UTF_8), false);
            assertTrue(wal.size() > firstSize, "Reopened WAL must append at the end");
        }
    }

    @Test
    @DisplayName("Should write binary records sequentially to disk matching WalEncoder layout")
    void shouldWriteSequentialBinaryRecordsMatchingLayout(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("binary_layout.wal");

        byte[] val1 = "alpha".getBytes(StandardCharsets.UTF_8);
        try (Wal wal = new Wal(walPath)) {
            wal.append("k1", val1, false);
            wal.append("k2", null, true);
        }

        byte[] allBytes = Files.readAllBytes(walPath);
        ByteBuffer buffer = ByteBuffer.wrap(allBytes);

        // Record 1 (PUT)
        int crc1 = buffer.getInt();
        byte opType1 = buffer.get();
        int keyLen1 = buffer.getInt();
        int valLen1 = buffer.getInt();
        assertEquals(OpType.PUT.getCode(), opType1);
        assertEquals(2, keyLen1);
        assertEquals(val1.length, valLen1);

        byte[] keyBytes1 = new byte[keyLen1];
        buffer.get(keyBytes1);
        assertArrayEquals("k1".getBytes(StandardCharsets.UTF_8), keyBytes1);

        byte[] valBytes1 = new byte[valLen1];
        buffer.get(valBytes1);
        assertArrayEquals(val1, valBytes1);

        // Record 2 (DELETE)
        int crc2 = buffer.getInt();
        byte opType2 = buffer.get();
        int keyLen2 = buffer.getInt();
        int valLen2 = buffer.getInt();
        assertEquals(OpType.DELETE.getCode(), opType2);
        assertEquals(2, keyLen2);
        assertEquals(0, valLen2);

        byte[] keyBytes2 = new byte[keyLen2];
        buffer.get(keyBytes2);
        assertArrayEquals("k2".getBytes(StandardCharsets.UTF_8), keyBytes2);

        assertEquals(0, buffer.remaining(), "No extra bytes at the end of file");
    }
}
