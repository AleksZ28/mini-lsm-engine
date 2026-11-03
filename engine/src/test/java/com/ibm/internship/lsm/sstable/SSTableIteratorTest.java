package com.ibm.internship.lsm.sstable;

import com.ibm.internship.lsm.wal.OpType;
import com.ibm.internship.lsm.wal.WalEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

public class SSTableIteratorTest {

    record TestRecord(String key, byte[] value, boolean isDelete) {}

    private Path createSSTable(Path path, List<TestRecord> records) throws IOException {
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
        return path;
    }

    @Test
    @DisplayName("Should sequentially iterate through all records in SSTable")
    void shouldIterateThroughAllRecords(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("full.sst");
        byte[] v1 = "value1".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "value2".getBytes(StandardCharsets.UTF_8);
        byte[] v4 = "value4".getBytes(StandardCharsets.UTF_8);

        List<TestRecord> records = List.of(
                new TestRecord("k1", v1, false),
                new TestRecord("k2", v2, false),
                new TestRecord("k3", null, true),
                new TestRecord("k4", v4, false)
        );
        createSSTable(sstPath, records);

        try (SSTableReader reader = SSTableReader.open(sstPath)) {
            List<SSTableEntry> collected = new ArrayList<>();
            for (SSTableEntry entry : reader) {
                collected.add(entry);
            }

            assertEquals(4, collected.size());

            assertEquals("k1", collected.get(0).key());
            assertEquals(ByteBuffer.wrap(v1), collected.get(0).value());
            assertFalse(collected.get(0).isTombstone());
            assertEquals(OpType.PUT, collected.get(0).opType());

            assertEquals("k2", collected.get(1).key());
            assertEquals(ByteBuffer.wrap(v2), collected.get(1).value());
            assertFalse(collected.get(1).isTombstone());
            assertEquals(OpType.PUT, collected.get(1).opType());

            assertEquals("k3", collected.get(2).key());
            assertNull(collected.get(2).value());
            assertTrue(collected.get(2).isTombstone());
            assertEquals(OpType.DELETE, collected.get(2).opType());

            assertEquals("k4", collected.get(3).key());
            assertEquals(ByteBuffer.wrap(v4), collected.get(3).value());
            assertFalse(collected.get(3).isTombstone());
            assertEquals(OpType.PUT, collected.get(3).opType());
        }
    }

    @Test
    @DisplayName("Should handle iteration over empty SSTable")
    void shouldHandleEmptySSTable(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("empty.sst");
        createSSTable(sstPath, List.of());

        try (SSTableReader reader = SSTableReader.open(sstPath)) {
            SSTableIterator it = reader.iterator();
            assertFalse(it.hasNext());
            assertThrows(NoSuchElementException.class, it::next);
        }
    }

    @Test
    @DisplayName("Should support range scan starting from specified key")
    void shouldIterateFromSpecifiedKey(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("range.sst");
        List<TestRecord> records = List.of(
                new TestRecord("apple", "vA".getBytes(StandardCharsets.UTF_8), false),
                new TestRecord("cherry", "vC".getBytes(StandardCharsets.UTF_8), false),
                new TestRecord("date", "vD".getBytes(StandardCharsets.UTF_8), false),
                new TestRecord("elderberry", "vE".getBytes(StandardCharsets.UTF_8), false)
        );
        createSSTable(sstPath, records);

        try (SSTableReader reader = SSTableReader.open(sstPath)) {
            List<String> keysFromCherry = new ArrayList<>();
            SSTableIterator it1 = reader.iterator("cherry");
            while (it1.hasNext()) {
                keysFromCherry.add(it1.next().key());
            }
            assertEquals(List.of("cherry", "date", "elderberry"), keysFromCherry);

            List<String> keysFromBanana = new ArrayList<>();
            SSTableIterator it2 = reader.iterator("banana");
            while (it2.hasNext()) {
                keysFromBanana.add(it2.next().key());
            }
            assertEquals(List.of("cherry", "date", "elderberry"), keysFromBanana);

            SSTableIterator it3 = reader.iterator("fig");
            assertFalse(it3.hasNext());
            assertThrows(NoSuchElementException.class, it3::next);

            List<String> keysFromStart = new ArrayList<>();
            SSTableIterator it4 = reader.iterator("aardvark");
            while (it4.hasNext()) {
                keysFromStart.add(it4.next().key());
            }
            assertEquals(List.of("apple", "cherry", "date", "elderberry"), keysFromStart);

            List<String> keysFromNull = new ArrayList<>();
            SSTableIterator it5 = reader.iterator(null);
            while (it5.hasNext()) {
                keysFromNull.add(it5.next().key());
            }
            assertEquals(List.of("apple", "cherry", "date", "elderberry"), keysFromNull);
        }
    }

    @Test
    @DisplayName("Should return read-only buffer that rejects modifications")
    void shouldReturnReadOnlyBuffer(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("readonly.sst");
        createSSTable(sstPath, List.of(new TestRecord("key", new byte[]{1, 2, 3}, false)));

        try (SSTableReader reader = SSTableReader.open(sstPath)) {
            SSTableIterator it = reader.iterator();
            assertTrue(it.hasNext());
            SSTableEntry entry = it.next();
            ByteBuffer buf = entry.value();
            assertNotNull(buf);
            assertThrows(ReadOnlyBufferException.class, () -> buf.put(0, (byte) 99));
        }
    }

    @Test
    @DisplayName("Should throw UncheckedIOException when encountering corrupted record during iteration")
    void shouldThrowOnCorruptedRecordDuringIteration(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("corrupt_it.sst");
        createSSTable(sstPath, List.of(
                new TestRecord("k1", "val1".getBytes(StandardCharsets.UTF_8), false),
                new TestRecord("k2", "val2".getBytes(StandardCharsets.UTF_8), false)
        ));

        long k2Offset;
        try (SSTableReader reader = SSTableReader.open(sstPath)) {
            k2Offset = reader.getIndex().find("k2").offset();
        }

        try (FileChannel channel = FileChannel.open(sstPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.allocate(1);
            channel.read(b, k2Offset + 13 + 2 + 1);
            byte original = b.get(0);
            b.flip();
            b.put(0, (byte) (original ^ 0xFF));
            channel.write(b, k2Offset + 13 + 2 + 1);
        }

        try (SSTableReader reader = SSTableReader.open(sstPath)) {
            SSTableIterator it = reader.iterator();
            assertTrue(it.hasNext());
            assertEquals("k1", it.next().key());

            assertTrue(it.hasNext());
            UncheckedIOException ex = assertThrows(UncheckedIOException.class, it::next);
            assertTrue(ex.getCause().getMessage().contains("Checksum mismatch"));
        }
    }

    @Test
    @DisplayName("Should throw IndexOutOfBoundsException when starting index is invalid")
    void shouldThrowOnInvalidStartIndex(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("bounds.sst");
        createSSTable(sstPath, List.of(new TestRecord("k1", "v1".getBytes(StandardCharsets.UTF_8), false)));

        try (SSTableReader reader = SSTableReader.open(sstPath)) {
            FileChannel ch = reader.getChannel();
            SSTableIndex idx = reader.getIndex();
            assertThrows(IndexOutOfBoundsException.class, () -> new SSTableIterator(ch, idx, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> new SSTableIterator(ch, idx, 2));
        }
    }
}
