package com.ibm.internship.lsm.compaction;

import com.ibm.internship.lsm.sstable.SSTableEntry;
import com.ibm.internship.lsm.sstable.SSTableReader;
import com.ibm.internship.lsm.sstable.SSTableWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

public class MergingIteratorTest {

    private final List<SSTableReader> openReaders = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (SSTableReader reader : openReaders) {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        }
        openReaders.clear();
    }

    private record TestData(String key, String value, boolean isDelete) {
        static TestData put(String key, String value) {
            return new TestData(key, value, false);
        }

        static TestData delete(String key) {
            return new TestData(key, null, true);
        }
    }

    private SSTableReader createSSTable(Path path, List<TestData> data) throws IOException {
        try (SSTableWriter writer = new SSTableWriter(path)) {
            for (TestData d : data) {
                byte[] valBytes = d.value() != null ? d.value().getBytes(StandardCharsets.UTF_8) : null;
                writer.append(d.key(), valBytes, d.isDelete());
            }
            writer.finish();
        }
        SSTableReader reader = SSTableReader.open(path);
        openReaders.add(reader);
        return reader;
    }

    private static String valueAsString(SSTableEntry entry) {
        if (entry.value() == null) {
            return null;
        }
        ByteBuffer duplicate = entry.value().duplicate();
        byte[] bytes = new byte[duplicate.remaining()];
        duplicate.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Should return empty iterator when input SSTables list is empty")
    void shouldReturnEmptyWhenInputIsEmpty() {
        MergingIterator iterator = new MergingIterator(List.of());

        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    @DisplayName("Should iterate over all entries in a single SSTable in correct order")
    void shouldIterateSingleSSTable(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("000001.sst");
        SSTableReader reader = createSSTable(sstPath, List.of(
                TestData.put("apple", "pie"),
                TestData.put("banana", "split"),
                TestData.put("cherry", "cake")
        ));

        MergingIterator iterator = new MergingIterator(List.of(reader));

        assertTrue(iterator.hasNext());
        SSTableEntry e1 = iterator.next();
        assertEquals("apple", e1.key());
        assertEquals("pie", valueAsString(e1));

        assertTrue(iterator.hasNext());
        SSTableEntry e2 = iterator.next();
        assertEquals("banana", e2.key());
        assertEquals("split", valueAsString(e2));

        assertTrue(iterator.hasNext());
        SSTableEntry e3 = iterator.next();
        assertEquals("cherry", e3.key());
        assertEquals("cake", valueAsString(e3));

        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    @DisplayName("Should merge non-overlapping SSTables in lexicographical order")
    void shouldMergeNonOverlappingSSTables(@TempDir Path tempDir) throws IOException {
        // Table 1 (newer, rank 0): "banana", "date"
        SSTableReader reader1 = createSSTable(tempDir.resolve("000002.sst"), List.of(
                TestData.put("banana", "v2"),
                TestData.put("date", "v4")
        ));

        // Table 2 (older, rank 1): "apple", "cherry"
        SSTableReader reader2 = createSSTable(tempDir.resolve("000001.sst"), List.of(
                TestData.put("apple", "v1"),
                TestData.put("cherry", "v3")
        ));

        MergingIterator iterator = new MergingIterator(List.of(reader1, reader2));

        List<String> keys = new ArrayList<>();
        while (iterator.hasNext()) {
            keys.add(iterator.next().key());
        }

        assertEquals(List.of("apple", "banana", "cherry", "date"), keys);
    }

    @Test
    @DisplayName("Should deduplicate keys and pick value from newest SSTable (rank 0)")
    void shouldDeduplicateAndPickNewestValue(@TempDir Path tempDir) throws IOException {
        // Table 1 (newer, rank 0): "user:1" -> "Aleksander", "user:2" -> "Bob"
        SSTableReader reader1 = createSSTable(tempDir.resolve("000002.sst"), List.of(
                TestData.put("user:1", "Aleksander"),
                TestData.put("user:2", "Bob")
        ));

        // Table 2 (older, rank 1): "user:1" -> "Aleks", "user:3" -> "Charlie"
        SSTableReader reader2 = createSSTable(tempDir.resolve("000001.sst"), List.of(
                TestData.put("user:1", "Aleks"),
                TestData.put("user:3", "Charlie")
        ));

        MergingIterator iterator = new MergingIterator(List.of(reader1, reader2));

        assertTrue(iterator.hasNext());
        SSTableEntry e1 = iterator.next();
        assertEquals("user:1", e1.key());
        assertEquals("Aleksander", valueAsString(e1)); // Newer value won!

        assertTrue(iterator.hasNext());
        SSTableEntry e2 = iterator.next();
        assertEquals("user:2", e2.key());
        assertEquals("Bob", valueAsString(e2));

        assertTrue(iterator.hasNext());
        SSTableEntry e3 = iterator.next();
        assertEquals("user:3", e3.key());
        assertEquals("Charlie", valueAsString(e3));

        assertFalse(iterator.hasNext());
    }

    @Test
    @DisplayName("Should deduplicate same key present across 3 SSTables")
    void shouldDeduplicateAcrossMultipleTables(@TempDir Path tempDir) throws IOException {
        // Table 1 (newest, rank 0): "count" -> "100"
        SSTableReader reader1 = createSSTable(tempDir.resolve("000003.sst"), List.of(
                TestData.put("count", "100")
        ));

        // Table 2 (middle, rank 1): "count" -> "50"
        SSTableReader reader2 = createSSTable(tempDir.resolve("000002.sst"), List.of(
                TestData.put("count", "50")
        ));

        // Table 3 (oldest, rank 2): "count" -> "10"
        SSTableReader reader3 = createSSTable(tempDir.resolve("000001.sst"), List.of(
                TestData.put("count", "10")
        ));

        MergingIterator iterator = new MergingIterator(List.of(reader1, reader2, reader3));

        assertTrue(iterator.hasNext());
        SSTableEntry entry = iterator.next();
        assertEquals("count", entry.key());
        assertEquals("100", valueAsString(entry));

        assertFalse(iterator.hasNext());
    }

    @Test
    @DisplayName("Should discard tombstone and hide key completely when deleted in newest SSTable")
    void shouldDropTombstoneAndPreviousValues(@TempDir Path tempDir) throws IOException {
        // Table 1 (newer, rank 0): "user:1" -> DELETE (tombstone), "user:2" -> "Active"
        SSTableReader reader1 = createSSTable(tempDir.resolve("000002.sst"), List.of(
                TestData.delete("user:1"),
                TestData.put("user:2", "Active")
        ));

        // Table 2 (older, rank 1): "user:1" -> "OldName", "user:3" -> "Legacy"
        SSTableReader reader2 = createSSTable(tempDir.resolve("000001.sst"), List.of(
                TestData.put("user:1", "OldName"),
                TestData.put("user:3", "Legacy")
        ));

        MergingIterator iterator = new MergingIterator(List.of(reader1, reader2));

        // user:1 should NOT be returned at all!
        assertTrue(iterator.hasNext());
        SSTableEntry e1 = iterator.next();
        assertEquals("user:2", e1.key());
        assertEquals("Active", valueAsString(e1));

        assertTrue(iterator.hasNext());
        SSTableEntry e2 = iterator.next();
        assertEquals("user:3", e2.key());
        assertEquals("Legacy", valueAsString(e2));

        assertFalse(iterator.hasNext());
    }

    @Test
    @DisplayName("Should return empty iterator when all keys in all tables are deleted by tombstones")
    void shouldBeEmptyWhenAllKeysAreTombstones(@TempDir Path tempDir) throws IOException {
        SSTableReader reader1 = createSSTable(tempDir.resolve("000002.sst"), List.of(
                TestData.delete("k1"),
                TestData.delete("k2")
        ));

        SSTableReader reader2 = createSSTable(tempDir.resolve("000001.sst"), List.of(
                TestData.put("k1", "v1"),
                TestData.put("k2", "v2")
        ));

        MergingIterator iterator = new MergingIterator(List.of(reader1, reader2));

        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }
}
