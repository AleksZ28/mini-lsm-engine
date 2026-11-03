package com.ibm.internship.lsm.sstable;

import com.ibm.internship.lsm.wal.OpType;
import com.ibm.internship.lsm.wal.WalDecoder;
import com.ibm.internship.lsm.wal.WalEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SSTableReaderTest {

    record TestRecord(String key, byte[] value, boolean isDelete) {}

    private Path createSSTable(Path path, List<TestRecord> records) throws IOException {
        List<TestRecord> sortedRecords = new ArrayList<>(records);
        sortedRecords.sort(java.util.Comparator.comparing(TestRecord::key));

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
        return path;
    }

    @Test
    @DisplayName("Should successfully retrieve existing keys and return read-only ByteBuffer")
    void shouldGetExistingKeys(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("00001.sst");
        byte[] v1 = "value_alpha".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "value_beta".getBytes(StandardCharsets.UTF_8);
        byte[] v3 = "value_gamma".getBytes(StandardCharsets.UTF_8);

        List<TestRecord> records = List.of(
                new TestRecord("key:1", v1, false),
                new TestRecord("key:2", v2, false),
                new TestRecord("key:3", v3, false)
        );
        createSSTable(sstPath, records);

        try (SSTableReader reader = SSTableReader.open(sstPath)) {
            ByteBuffer b1 = reader.get("key:1");
            assertNotNull(b1);
            assertEquals(ByteBuffer.wrap(v1), b1);
            assertThrows(ReadOnlyBufferException.class, () -> b1.put(0, (byte) 0xFF));

            ByteBuffer b2 = reader.get("key:2");
            assertNotNull(b2);
            assertEquals(ByteBuffer.wrap(v2), b2);

            ByteBuffer b3 = reader.get("key:3");
            assertNotNull(b3);
            assertEquals(ByteBuffer.wrap(v3), b3);

            assertTrue(reader.containsKey("key:1"));
            assertTrue(reader.containsKey("key:2"));
            assertTrue(reader.containsKey("key:3"));
            assertFalse(reader.containsKey("key:4"));
        }
    }

    @Test
    @DisplayName("Should return null for non-existent and null keys without errors")
    void shouldReturnNullForMissingKeys(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("missing.sst");
        createSSTable(sstPath, List.of(
                new TestRecord("a", "valA".getBytes(StandardCharsets.UTF_8), false),
                new TestRecord("z", "valZ".getBytes(StandardCharsets.UTF_8), false)
        ));

        try (SSTableReader reader = SSTableReader.open(sstPath)) {
            assertNull(reader.get("missing"));
            assertNull(reader.get(null));
            assertNull(reader.readRecord("missing"));
            assertNull(reader.readRecord(null));
            assertFalse(reader.containsKey("missing"));
            assertFalse(reader.containsKey(null));
        }
    }

    @Test
    @DisplayName("Should return null on get() for tombstone records and expose OpType.DELETE on readRecord()")
    void shouldHandleTombstonesCorrectly(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("tombstone.sst");
        byte[] vActive = "alive".getBytes(StandardCharsets.UTF_8);

        createSSTable(sstPath, List.of(
                new TestRecord("deleted_key", null, true),
                new TestRecord("live_key", vActive, false)
        ));

        try (SSTableReader reader = SSTableReader.open(sstPath)) {
            assertNull(reader.get("deleted_key"));
            assertTrue(reader.containsKey("deleted_key")); // It is present in index

            WalDecoder.DecodedRecord delRecord = reader.readRecord("deleted_key");
            assertNotNull(delRecord);
            assertEquals("deleted_key", delRecord.getKey());
            assertEquals(OpType.DELETE, delRecord.getOpType());
            assertEquals(0, delRecord.getValueBytes().length);

            assertEquals(ByteBuffer.wrap(vActive), reader.get("live_key"));
            WalDecoder.DecodedRecord liveRecord = reader.readRecord("live_key");
            assertNotNull(liveRecord);
            assertEquals(OpType.PUT, liveRecord.getOpType());
        }
    }

    @Test
    @DisplayName("Should throw IOException on corrupted record checksum in data section")
    void shouldThrowOnCorruptedRecordChecksum(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("corrupt_record.sst");
        byte[] val1 = "unmolested".getBytes(StandardCharsets.UTF_8);
        byte[] val2 = "target_of_corruption".getBytes(StandardCharsets.UTF_8);

        createSSTable(sstPath, List.of(
                new TestRecord("k1", val1, false),
                new TestRecord("k2", val2, false)
        ));

        try (FileChannel channel = FileChannel.open(sstPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long k2Offset;
            try (SSTableReader r = SSTableReader.open(sstPath)) {
                k2Offset = r.getIndex().find("k2").offset();
            }

            ByteBuffer b = ByteBuffer.allocate(1);
            channel.read(b, k2Offset + 13 + 2 + 1);
            byte original = b.get(0);
            b.flip();
            b.put(0, (byte) (original ^ 0xFF));
            channel.write(b, k2Offset + 13 + 2 + 1);
        }

        try (SSTableReader reader = SSTableReader.open(sstPath)) {
            assertEquals(ByteBuffer.wrap(val1), reader.get("k1"));

            IOException ex = assertThrows(IOException.class, () -> reader.get("k2"));
            assertTrue(ex.getMessage().contains("Checksum mismatch"), ex.getMessage());
        }
    }

    @Test
    @DisplayName("Should handle Unicode keys and large payloads")
    void shouldHandleLargePayloadsAndUnicode(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("large.sst");
        String unicodeKey1 = "użytkownik:ąćęłńóśźż";
        String unicodeKey2 = "🔑:secure:key";

        byte[] largeValue = new byte[65536]; // 64 KB
        for (int i = 0; i < largeValue.length; i++) {
            largeValue[i] = (byte) (i % 251);
        }

        createSSTable(sstPath, List.of(
                new TestRecord(unicodeKey2, "small_val".getBytes(StandardCharsets.UTF_8), false),
                new TestRecord(unicodeKey1, largeValue, false)
        ));

        try (SSTableReader reader = SSTableReader.open(sstPath)) {
            ByteBuffer result = reader.get(unicodeKey1);
            assertNotNull(result);
            assertEquals(ByteBuffer.wrap(largeValue), result);

            ByteBuffer result2 = reader.get(unicodeKey2);
            assertNotNull(result2);
            assertEquals(ByteBuffer.wrap("small_val".getBytes(StandardCharsets.UTF_8)), result2);
        }
    }

    @Test
    @DisplayName("Should throw IOException when attempting to read after reader is closed")
    void shouldThrowWhenClosed(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("closed.sst");
        createSSTable(sstPath, List.of(new TestRecord("key", "val".getBytes(StandardCharsets.UTF_8), false)));

        SSTableReader reader = SSTableReader.open(sstPath);
        reader.close();

        assertThrows(IOException.class, () -> reader.get("key"));
    }
}
