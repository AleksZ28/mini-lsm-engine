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

public class WalReaderTest {

    @Test
    @DisplayName("Should return null on empty WAL file")
    void shouldReturnNullOnEmptyFile(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("empty.wal");
        Files.createFile(walPath);

        try (WalReader reader = new WalReader(walPath)) {
            assertNull(reader.readNextRecord());
        }
    }

    @Test
    @DisplayName("Should read written PUT and DELETE records sequentially")
    void shouldReadPutAndDeleteRecords(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");
        byte[] value1 = "value-one".getBytes(StandardCharsets.UTF_8);

        try (Wal wal = new Wal(walPath)) {
            wal.append("user:1", value1, false);
            wal.append("user:2", null, true);
        }

        try (WalReader reader = new WalReader(walPath)) {
            WalReader.DecodedRecord record1 = reader.readNextRecord();
            assertNotNull(record1);
            assertEquals(OpType.PUT, record1.getOpType());
            assertEquals("user:1", record1.getKey());
            assertArrayEquals(value1, record1.getValueBytes());

            WalReader.DecodedRecord record2 = reader.readNextRecord();
            assertNotNull(record2);
            assertEquals(OpType.DELETE, record2.getOpType());
            assertEquals("user:2", record2.getKey());
            assertEquals(0, record2.getValueBytes().length);

            assertNull(reader.readNextRecord());
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
        }

        MemTable memTable = new MemTable();
        try (WalReader reader = new WalReader(walPath)) {
            reader.recover(memTable);
        }

        assertArrayEquals(updatedVal, memTable.get("k1"));
        assertNull(memTable.get("k2"));
    }

    @Test
    @DisplayName("Should throw IOException on corrupted checksum")
    void shouldThrowOnCorruptedChecksum(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("corrupt.wal");

        try (Wal wal = new Wal(walPath)) {
            wal.append("safe-key", "safe-value".getBytes(StandardCharsets.UTF_8), false);
        }

        byte[] allBytes = Files.readAllBytes(walPath);
        allBytes[allBytes.length - 1] = (byte) (allBytes[allBytes.length - 1] ^ 0xFF);
        Files.write(walPath, allBytes);

        try (WalReader reader = new WalReader(walPath)) {
            assertThrows(IOException.class, reader::readNextRecord);
        }
    }

    @Test
    @DisplayName("Should throw IOException on truncated header")
    void shouldThrowOnTruncatedHeader(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("truncated-header.wal");

        byte[] partialHeader = new byte[]{1, 2, 3, 4, 5};
        Files.write(walPath, partialHeader);

        try (WalReader reader = new WalReader(walPath)) {
            assertThrows(IOException.class, reader::readNextRecord);
        }
    }

    @Test
    @DisplayName("Should throw IOException on truncated payload")
    void shouldThrowOnTruncatedPayload(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("truncated-payload.wal");

        try (Wal wal = new Wal(walPath)) {
            wal.append("long-key-name", "some-payload-data".getBytes(StandardCharsets.UTF_8), false);
        }

        try (FileChannel channel = FileChannel.open(walPath, StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() - 5);
        }

        try (WalReader reader = new WalReader(walPath)) {
            assertThrows(IOException.class, reader::readNextRecord);
        }
    }
}
