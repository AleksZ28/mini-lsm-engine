package com.ibm.internship.lsm.sstable;

import com.ibm.internship.lsm.wal.OpType;
import com.ibm.internship.lsm.wal.WalDecoder;
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

public class SSTableWriterTest {

    private static final long EXPECTED_MAGIC_NUMBER = 0x4D494E494C534D31L;

    @Test
    @DisplayName("Should write SSTable with valid Data Section, Filter Block, Index Block and Footer")
    void shouldWriteSSTableWithCorrectLayoutAndFooter(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("000001.sst");

        byte[] v1 = "value_one".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "value_two_longer".getBytes(StandardCharsets.UTF_8);

        try (SSTableWriter writer = new SSTableWriter(sstPath)) {
            writer.append("key1", v1, false);
            writer.append("key2", v2, false);
            writer.append("key3", null, true); // Tombstone / delete
            writer.finish();
        }

        long fileSize = Files.size(sstPath);
        assertTrue(fileSize > SSTableFooter.FOOTER_SIZE, "File size should be larger than footer size");

        try (FileChannel channel = FileChannel.open(sstPath, StandardOpenOption.READ)) {
            // 1. Read Footer (last 36 bytes)
            ByteBuffer footerBuffer = ByteBuffer.allocate(SSTableFooter.FOOTER_SIZE);
            channel.position(fileSize - SSTableFooter.FOOTER_SIZE);
            channel.read(footerBuffer);
            footerBuffer.flip();

            long filterOffset = footerBuffer.getLong();
            int filterLength = footerBuffer.getInt();
            long indexOffset = footerBuffer.getLong();
            int indexLength = footerBuffer.getInt();
            int entryCount = footerBuffer.getInt();
            long magicNumber = footerBuffer.getLong();

            assertEquals(EXPECTED_MAGIC_NUMBER, magicNumber, "Magic number must match MINILSM1");
            assertEquals(3, entryCount, "Entry count must match number of appended records");
            assertEquals(fileSize - SSTableFooter.FOOTER_SIZE, indexOffset + indexLength, "Index block must end right where footer starts");
            assertEquals(indexOffset, filterOffset + filterLength, "Filter block must end right where Index block starts");

            // 2. Read and Verify Filter Block
            ByteBuffer filterBuffer = ByteBuffer.allocate(filterLength);
            channel.position(filterOffset);
            channel.read(filterBuffer);
            filterBuffer.flip();
            byte[] filterBytes = new byte[filterBuffer.remaining()];
            filterBuffer.get(filterBytes);

            BloomFilter bloomFilter = BloomFilter.fromByteArray(filterBytes);
            assertTrue(bloomFilter.mightContain("key1"), "Filter should contain key1");
            assertTrue(bloomFilter.mightContain("key2"), "Filter should contain key2");
            assertTrue(bloomFilter.mightContain("key3"), "Filter should contain key3");
            assertFalse(bloomFilter.mightContain("absent_key"), "Filter should not contain absent_key");

            // 3. Read Index Block
            ByteBuffer indexBuffer = ByteBuffer.allocate(indexLength);
            channel.position(indexOffset);
            channel.read(indexBuffer);
            indexBuffer.flip();

            // Record 1 index entry
            int k1Len = indexBuffer.getInt();
            assertEquals("key1".length(), k1Len);
            byte[] k1Bytes = new byte[k1Len];
            indexBuffer.get(k1Bytes);
            assertEquals("key1", new String(k1Bytes, StandardCharsets.UTF_8));
            long k1Offset = indexBuffer.getLong();
            assertEquals(0, k1Offset, "First record must start at offset 0");
            int k1RecLen = indexBuffer.getInt();

            // Record 2 index entry
            int k2Len = indexBuffer.getInt();
            assertEquals("key2".length(), k2Len);
            byte[] k2Bytes = new byte[k2Len];
            indexBuffer.get(k2Bytes);
            assertEquals("key2", new String(k2Bytes, StandardCharsets.UTF_8));
            long k2Offset = indexBuffer.getLong();
            assertEquals(k1RecLen, k2Offset, "Second record offset must be after first record");
            int k2RecLen = indexBuffer.getInt();

            // Record 3 index entry (Tombstone)
            int k3Len = indexBuffer.getInt();
            assertEquals("key3".length(), k3Len);
            byte[] k3Bytes = new byte[k3Len];
            indexBuffer.get(k3Bytes);
            assertEquals("key3", new String(k3Bytes, StandardCharsets.UTF_8));
            long k3Offset = indexBuffer.getLong();
            assertEquals(k2Offset + k2RecLen, k3Offset);
            int k3RecLen = indexBuffer.getInt();

            assertEquals(filterOffset, k3Offset + k3RecLen, "Data section must end right where Filter block starts");

            // 4. Verify Data Section records using WalDecoder
            // Verify record 1
            ByteBuffer r1Buffer = ByteBuffer.allocate(k1RecLen);
            channel.position(k1Offset);
            channel.read(r1Buffer);
            r1Buffer.flip();
            WalDecoder.DecodedRecord rec1 = WalDecoder.decode(r1Buffer);
            assertEquals("key1", rec1.getKey());
            assertEquals(OpType.PUT, rec1.getOpType());
            assertArrayEquals(v1, rec1.getValueBytes());

            // Verify record 2
            ByteBuffer r2Buffer = ByteBuffer.allocate(k2RecLen);
            channel.position(k2Offset);
            channel.read(r2Buffer);
            r2Buffer.flip();
            WalDecoder.DecodedRecord rec2 = WalDecoder.decode(r2Buffer);
            assertEquals("key2", rec2.getKey());
            assertEquals(OpType.PUT, rec2.getOpType());
            assertArrayEquals(v2, rec2.getValueBytes());

            // Verify record 3 (tombstone)
            ByteBuffer r3Buffer = ByteBuffer.allocate(k3RecLen);
            channel.position(k3Offset);
            channel.read(r3Buffer);
            r3Buffer.flip();
            WalDecoder.DecodedRecord rec3 = WalDecoder.decode(r3Buffer);
            assertEquals("key3", rec3.getKey());
            assertEquals(OpType.DELETE, rec3.getOpType());
            assertEquals(0, rec3.getValueBytes().length);
        }
    }

    @Test
    @DisplayName("Should write empty SSTable with zero entries and valid 36-byte footer")
    void shouldWriteEmptySSTable(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("empty.sst");

        try (SSTableWriter writer = new SSTableWriter(sstPath)) {
            writer.finish();
        }

        long fileSize = Files.size(sstPath);
        assertTrue(fileSize >= SSTableFooter.FOOTER_SIZE, "Empty SSTable must be at least the footer size");

        try (FileChannel channel = FileChannel.open(sstPath, StandardOpenOption.READ)) {
            ByteBuffer footerBuffer = ByteBuffer.allocate(SSTableFooter.FOOTER_SIZE);
            channel.position(fileSize - SSTableFooter.FOOTER_SIZE);
            channel.read(footerBuffer);
            footerBuffer.flip();

            long filterOffset = footerBuffer.getLong();
            int filterLength = footerBuffer.getInt();
            long indexOffset = footerBuffer.getLong();
            int indexLength = footerBuffer.getInt();
            int entryCount = footerBuffer.getInt();
            long magicNumber = footerBuffer.getLong();

            assertEquals(0, entryCount);
            assertEquals(0, indexLength);
            assertEquals(filterOffset + filterLength, indexOffset);
            assertEquals(EXPECTED_MAGIC_NUMBER, magicNumber);
        }
    }

    @Test
    @DisplayName("Should handle long keys and large payloads correctly")
    void shouldHandleLargePayloadsAndLongKeys(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("large.sst");

        String longKey = "user:profile:very:long:identifier:0000000000123456789";
        byte[] largeValue = new byte[8192];
        for (int i = 0; i < largeValue.length; i++) {
            largeValue[i] = (byte) (i % 127);
        }

        try (SSTableWriter writer = new SSTableWriter(sstPath)) {
            writer.append(longKey, largeValue, false);
            writer.finish();
        }

        try (FileChannel channel = FileChannel.open(sstPath, StandardOpenOption.READ)) {
            ByteBuffer footerBuffer = ByteBuffer.allocate(SSTableFooter.FOOTER_SIZE);
            channel.position(channel.size() - SSTableFooter.FOOTER_SIZE);
            channel.read(footerBuffer);
            footerBuffer.flip();

            long filterOffset = footerBuffer.getLong();
            int filterLength = footerBuffer.getInt();
            long indexOffset = footerBuffer.getLong();
            int indexLength = footerBuffer.getInt();
            int entryCount = footerBuffer.getInt();

            assertEquals(1, entryCount);
            assertTrue(filterLength > 0);

            // Read Index
            ByteBuffer indexBuffer = ByteBuffer.allocate(indexLength);
            channel.position(indexOffset);
            channel.read(indexBuffer);
            indexBuffer.flip();

            int keyLen = indexBuffer.getInt();
            byte[] keyBytes = new byte[keyLen];
            indexBuffer.get(keyBytes);
            assertEquals(longKey, new String(keyBytes, StandardCharsets.UTF_8));
            long offset = indexBuffer.getLong();
            int recLen = indexBuffer.getInt();

            // Read Data
            ByteBuffer recBuffer = ByteBuffer.allocate(recLen);
            channel.position(offset);
            channel.read(recBuffer);
            recBuffer.flip();

            WalDecoder.DecodedRecord record = WalDecoder.decode(recBuffer);
            assertEquals(longKey, record.getKey());
            assertArrayEquals(largeValue, record.getValueBytes());
        }
    }
}
