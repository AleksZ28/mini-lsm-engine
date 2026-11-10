package com.ibm.internship.lsm.sstable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SSTableIndexTest {

    private byte[] createIndexBlock(List<IndexEntry> entries) {
        int totalSize = 0;
        for (IndexEntry entry : entries) {
            totalSize += 4 + entry.key().getBytes(StandardCharsets.UTF_8).length + 8 + 4;
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        for (IndexEntry entry : entries) {
            byte[] keyBytes = entry.key().getBytes(StandardCharsets.UTF_8);
            buffer.putInt(keyBytes.length);
            buffer.put(keyBytes);
            buffer.putLong(entry.offset());
            buffer.putInt(entry.recordLength());
        }

        return buffer.array();
    }

    private void writeSSTableFile(Path path, byte[] dataSection, byte[] indexBlock, int entryCount) throws IOException {
        long dataSize = dataSection == null ? 0 : dataSection.length;
        int indexLength = indexBlock == null ? 0 : indexBlock.length;

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            if (dataSection != null && dataSection.length > 0) {
                channel.write(ByteBuffer.wrap(dataSection));
            }

            if (indexBlock != null && indexBlock.length > 0) {
                channel.write(ByteBuffer.wrap(indexBlock));
            }

            ByteBuffer footer = ByteBuffer.allocate(SSTableFooter.FOOTER_SIZE);
            footer.putLong(0L); // filterOffset
            footer.putInt(0);   // filterLength
            footer.putLong(dataSize);
            footer.putInt(indexLength);
            footer.putInt(entryCount);
            footer.putLong(SSTableFooter.MAGIC_NUMBER);
            footer.flip();

            while (footer.hasRemaining()) {
                channel.write(footer);
            }
        }
    }

    @Test
    @DisplayName("Should successfully parse single index entry and search it")
    void shouldParseSingleEntry() throws IOException {
        IndexEntry entry = new IndexEntry("key1", 0, 20);
        byte[] indexBytes = createIndexBlock(List.of(entry));
        SSTableFooter footer = new SSTableFooter(50, indexBytes.length, 1);

        SSTableIndex index = SSTableIndex.decode(ByteBuffer.wrap(indexBytes), footer);

        assertEquals(1, index.size());
        assertFalse(index.isEmpty());
        assertEquals(entry, index.get(0));
        assertEquals(entry, index.find("key1"));
        assertNull(index.find("non_existent"));
        assertNull(index.find(null));
    }

    @Test
    @DisplayName("Should successfully binary search multiple sorted index entries")
    void shouldBinarySearchMultipleEntries() throws IOException {
        List<IndexEntry> entries = List.of(
                new IndexEntry("apple", 0, 15),
                new IndexEntry("banana", 15, 20),
                new IndexEntry("cherry", 35, 18),
                new IndexEntry("date", 53, 14),
                new IndexEntry("elderberry", 67, 22)
        );

        byte[] indexBytes = createIndexBlock(entries);
        long dataSectionSize = 89;
        SSTableFooter footer = new SSTableFooter(dataSectionSize, indexBytes.length, entries.size());

        SSTableIndex index = SSTableIndex.decode(ByteBuffer.wrap(indexBytes), footer);

        assertEquals(5, index.size());

        for (IndexEntry expected : entries) {
            IndexEntry found = index.find(expected.key());
            assertNotNull(found);
            assertEquals(expected.key(), found.key());
            assertEquals(expected.offset(), found.offset());
            assertEquals(expected.recordLength(), found.recordLength());
        }

        assertNull(index.find("aardvark"));   // before first
        assertNull(index.find("blueberry"));  // between banana and cherry
        assertNull(index.find("fig"));        // after last
    }

    @Test
    @DisplayName("Should return empty SSTableIndex when entryCount is 0")
    void shouldHandleEmptyIndex() throws IOException {
        SSTableFooter footer = new SSTableFooter(0, 0, 0);
        SSTableIndex index = SSTableIndex.decode(ByteBuffer.allocate(0), footer);

        assertTrue(index.isEmpty());
        assertEquals(0, index.size());
        assertNull(index.find("key"));
    }

    @Test
    @DisplayName("Should throw IOException when keys in index are not strictly ascending")
    void shouldThrowWhenKeysOutOfOrder() {
        ByteBuffer buf = ByteBuffer.allocate(64);

        byte[] k1 = "banana".getBytes(StandardCharsets.UTF_8);
        buf.putInt(k1.length);
        buf.put(k1);
        buf.putLong(0);
        buf.putInt(15);

        byte[] k2 = "apple".getBytes(StandardCharsets.UTF_8);
        buf.putInt(k2.length);
        buf.put(k2);
        buf.putLong(15);
        buf.putInt(15);

        buf.flip();

        SSTableFooter footer = new SSTableFooter(50, buf.remaining(), 2);
        IOException ex = assertThrows(IOException.class, () -> SSTableIndex.decode(buf, footer));
        assertTrue(ex.getMessage().contains("not strictly greater than previous key"), ex.getMessage());
    }

    @Test
    @DisplayName("Should throw IOException when duplicate keys exist in index")
    void shouldThrowWhenDuplicateKeys() {
        ByteBuffer buf = ByteBuffer.allocate(64);

        byte[] k1 = "apple".getBytes(StandardCharsets.UTF_8);
        buf.putInt(k1.length);
        buf.put(k1);
        buf.putLong(0);
        buf.putInt(15);

        buf.putInt(k1.length);
        buf.put(k1);
        buf.putLong(15);
        buf.putInt(15);

        buf.flip();

        SSTableFooter footer = new SSTableFooter(50, buf.remaining(), 2);
        IOException ex = assertThrows(IOException.class, () -> SSTableIndex.decode(buf, footer));
        assertTrue(ex.getMessage().contains("not strictly greater than previous key"), ex.getMessage());
    }

    @Test
    @DisplayName("Should throw IOException when record extends past data section")
    void shouldThrowWhenRecordExtendsPastDataSection() {
        IndexEntry entry = new IndexEntry("key1", 90, 20); // 90 + 20 = 110, but index starts at 100
        byte[] indexBytes = createIndexBlock(List.of(entry));
        SSTableFooter footer = new SSTableFooter(100, indexBytes.length, 1);

        IOException ex = assertThrows(IOException.class, () -> SSTableIndex.decode(ByteBuffer.wrap(indexBytes), footer));
        assertTrue(ex.getMessage().contains("extends beyond data section"), ex.getMessage());
    }

    @Test
    @DisplayName("Should throw IOException when index entry has negative key length")
    void shouldThrowOnNegativeKeyLength() {
        ByteBuffer buf = ByteBuffer.allocate(32);
        buf.putInt(-1); // negative key length
        buf.putLong(0);
        buf.putInt(15);
        buf.flip();

        SSTableFooter footer = new SSTableFooter(50, buf.remaining(), 1);
        IOException ex = assertThrows(IOException.class, () -> SSTableIndex.decode(buf, footer));
        assertTrue(ex.getMessage().contains("negative key length"), ex.getMessage());
    }

    @Test
    @DisplayName("Should throw IOException when trailing unparsed bytes remain in index block")
    void shouldThrowWhenTrailingBytesRemain() {
        IndexEntry entry = new IndexEntry("key1", 0, 15);
        byte[] indexBytes = createIndexBlock(List.of(entry));

        ByteBuffer buf = ByteBuffer.allocate(indexBytes.length + 4);
        buf.put(indexBytes);
        buf.putInt(0xDEADBEEF);
        buf.flip();

        SSTableFooter footer = new SSTableFooter(50, buf.remaining(), 1);
        IOException ex = assertThrows(IOException.class, () -> SSTableIndex.decode(buf, footer));
        assertTrue(ex.getMessage().contains("unparsed trailing bytes"), ex.getMessage());
    }

    @Test
    @DisplayName("Should integrate properly with SSTableReader")
    void shouldIntegrateWithSSTableReader(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("table.sst");
        byte[] dummyData = new byte[80];

        List<IndexEntry> entries = List.of(
                new IndexEntry("k1", 0, 20),
                new IndexEntry("k2", 20, 25),
                new IndexEntry("k3", 45, 35)
        );
        byte[] indexBytes = createIndexBlock(entries);

        writeSSTableFile(sstPath, dummyData, indexBytes, entries.size());

        try (SSTableReader reader = SSTableReader.open(sstPath)) {
            SSTableIndex index = reader.getIndex();
            assertNotNull(index);
            assertEquals(3, index.size());
            assertEquals(new IndexEntry("k1", 0, 20), index.find("k1"));
            assertEquals(new IndexEntry("k2", 20, 25), index.find("k2"));
            assertEquals(new IndexEntry("k3", 45, 35), index.find("k3"));
            assertNull(index.find("k4"));
        }
    }
}
