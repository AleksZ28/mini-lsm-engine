package com.ibm.internship.lsm.wal;

import com.ibm.internship.lsm.memtable.MemTable;
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

import static org.junit.jupiter.api.Assertions.*;

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

            assertEquals(ByteBuffer.wrap(updatedVal), memTable.get("k1"));
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

    @Test
    @DisplayName("Should recover valid records and truncate incomplete trailing record on crash")
    void shouldRecoverAndTruncateIncompleteTrailingRecord(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("torn-write.wal");
        byte[] v1 = "val1".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "val2".getBytes(StandardCharsets.UTF_8);

        long validSize;
        try (Wal wal = new Wal(walPath)) {
            wal.append("k1", v1, false);
            wal.append("k2", v2, false);
            validSize = wal.size();
        }

        // Simulate crash during 3rd write: append partial header (e.g. 7 bytes of garbage)
        Files.write(walPath, new byte[]{0x12, 0x34, 0x56, 0x78, 0x01, 0x00, 0x05}, StandardOpenOption.APPEND);
        assertTrue(Files.size(walPath) > validSize);

        try (Wal wal = new Wal(walPath)) {
            MemTable memTable = new MemTable();
            wal.recover(memTable);

            // Valid records before the torn write must be restored
            assertEquals(ByteBuffer.wrap(v1), memTable.get("k1"));
            assertEquals(ByteBuffer.wrap(v2), memTable.get("k2"));

            // File must be truncated back to the last valid offset
            assertEquals(validSize, wal.size());
            assertEquals(validSize, wal.position());
            assertEquals(validSize, Files.size(walPath));

            // Subsequent writes after recovery must work and append cleanly
            wal.append("k3", "val3".getBytes(StandardCharsets.UTF_8), false);
            assertTrue(wal.size() > validSize);
        }
    }

    @Test
    @DisplayName("Should fail recovery when corruption is in the middle of log")
    void shouldFailRecoveryWhenCorruptionIsInMiddleOfLog(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("corrupted-middle.wal");

        try (Wal wal = new Wal(walPath)) {
            wal.append("k1", "v1".getBytes(StandardCharsets.UTF_8), false);
            wal.append("k2", "v2".getBytes(StandardCharsets.UTF_8), false);
            wal.append("k3", "v3".getBytes(StandardCharsets.UTF_8), false);
        }

        // Corrupt the CRC of the middle record (Record 2 starts at offset 17: 13 header + 2 key + 2 val)
        byte[] allBytes = Files.readAllBytes(walPath);
        int record2CrcOffset = 17;
        allBytes[record2CrcOffset] = (byte) (allBytes[record2CrcOffset] ^ 0xFF);
        Files.write(walPath, allBytes);

        try (Wal wal = new Wal(walPath)) {
            MemTable memTable = new MemTable();
            // Recovery must throw IOException and not silently drop valid subsequent records
            assertThrows(IOException.class, () -> wal.recover(memTable));
        }
    }
}
