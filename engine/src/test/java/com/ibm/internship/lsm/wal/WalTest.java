package com.ibm.internship.lsm.wal;

import com.ibm.internship.lsm.memtable.MemTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WalTest {

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
    @DisplayName("Should read written PUT and DELETE records sequentially")
    void shouldReadRecordsSequentially(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("records.wal");
        byte[] value1 = "val1".getBytes(StandardCharsets.UTF_8);

        try (Wal wal = new Wal(walPath)) {
            wal.append("user:1", value1, false);
            wal.append("user:2", null, true);

            wal.position(0);

            WalDecoder.DecodedRecord rec1 = wal.readNextRecord();
            assertNotNull(rec1);
            assertEquals(OpType.PUT, rec1.getOpType());
            assertEquals("user:1", rec1.getKey());
            assertArrayEquals(value1, rec1.getValueBytes());

            WalDecoder.DecodedRecord rec2 = wal.readNextRecord();
            assertNotNull(rec2);
            assertEquals(OpType.DELETE, rec2.getOpType());
            assertEquals("user:2", rec2.getKey());
            assertEquals(0, rec2.getValueBytes().length);

            assertNull(wal.readNextRecord());
        }
    }

    @Test
    @DisplayName("Should recover entries into MemTable in log order")
    void shouldRecoverIntoMemTable(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("recovery.wal");
        byte[] originalVal = "original".getBytes(StandardCharsets.UTF_8);
        byte[] updatedVal = "updated".getBytes(StandardCharsets.UTF_8);

        try (Wal wal = new Wal(walPath)) {
            wal.append("k1", originalVal, false);
            wal.append("k2", originalVal, false);
            wal.append("k1", updatedVal, false);
            wal.append("k2", null, true);

            MemTable memTable = new MemTable();
            wal.recover(memTable);

            assertArrayEquals(updatedVal, memTable.get("k1"));
            assertNull(memTable.get("k2"));

            // After recovery, wal position must be at the end so next appends work
            assertEquals(wal.size(), wal.position());
            wal.append("k3", "v3".getBytes(StandardCharsets.UTF_8), false);
            assertTrue(wal.size() > wal.position() - 100);
        }
    }

    @Test
    @DisplayName("Should throw IOException on corrupted checksum during read")
    void shouldThrowOnCorruptedChecksumDuringRead(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("corrupt.wal");

        try (Wal wal = new Wal(walPath)) {
            wal.append("safe-key", "safe-value".getBytes(StandardCharsets.UTF_8), false);
        }

        // Corrupt one byte in the file after the checksum
        byte[] allBytes = Files.readAllBytes(walPath);
        allBytes[allBytes.length - 1] = (byte) (allBytes[allBytes.length - 1] ^ 0xFF);
        Files.write(walPath, allBytes);

        try (Wal wal = new Wal(walPath)) {
            wal.position(0);
            assertThrows(IOException.class, wal::readNextRecord);
        }
    }

    @Test
    @DisplayName("Should throw IOException on truncated header during read")
    void shouldThrowOnTruncatedHeaderDuringRead(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("truncated-header.wal");
        Files.write(walPath, new byte[]{1, 2, 3, 4, 5});

        try (Wal wal = new Wal(walPath)) {
            wal.position(0);
            assertThrows(IOException.class, wal::readNextRecord);
        }
    }

    @Test
    @DisplayName("Should throw IOException on truncated payload during read")
    void shouldThrowOnTruncatedPayloadDuringRead(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("truncated-payload.wal");

        try (Wal wal = new Wal(walPath)) {
            wal.append("long-key-name", "some-payload-data".getBytes(StandardCharsets.UTF_8), false);
        }

        try (FileChannel channel = FileChannel.open(walPath, StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() - 5);
        }

        try (Wal wal = new Wal(walPath)) {
            wal.position(0);
            assertThrows(IOException.class, wal::readNextRecord);
        }
    }
}
