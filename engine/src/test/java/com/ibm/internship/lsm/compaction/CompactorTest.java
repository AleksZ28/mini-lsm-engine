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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CompactorTest {

    private final List<SSTableReader> openReaders = new ArrayList<>();
    private final Compactor compactor = new Compactor();

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

    private static String bufferToString(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        ByteBuffer dup = buffer.duplicate();
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when sstables list is empty")
    void shouldThrowWhenEmpty(@TempDir Path tempDir) {
        Path target = tempDir.resolve("compacted.sst");
        assertThrows(IllegalArgumentException.class, () -> compactor.compact(List.of(), target));
        assertFalse(Files.exists(target));
    }

    @Test
    @DisplayName("Should throw NullPointerException when arguments are null")
    void shouldThrowWhenNull(@TempDir Path tempDir) {
        Path target = tempDir.resolve("compacted.sst");
        assertThrows(NullPointerException.class, () -> compactor.compact(null, target));
        assertThrows(NullPointerException.class, () -> compactor.compact(List.of(), null));
    }

    @Test
    @DisplayName("Should compact two SSTables into one with latest values and deduplication")
    void shouldCompactTwoSSTables(@TempDir Path tempDir) throws IOException {
        // Table 1 (newer, rank 0): "k1" updated to "v2", "k2" added
        SSTableReader r1 = createSSTable(tempDir.resolve("000002.sst"), List.of(
                TestData.put("k1", "v2"),
                TestData.put("k2", "val2")
        ));

        // Table 2 (older, rank 1): "k1" was "v1", "k3" existed
        SSTableReader r2 = createSSTable(tempDir.resolve("000001.sst"), List.of(
                TestData.put("k1", "v1"),
                TestData.put("k3", "val3")
        ));

        Path compactedPath = tempDir.resolve("compacted.sst");
        SSTableReader compactedReader = compactor.compact(List.of(r1, r2), compactedPath);
        openReaders.add(compactedReader);

        // Verification: file exists on disk
        assertTrue(Files.exists(compactedPath));

        // Values verification
        assertEquals("v2", bufferToString(compactedReader.get("k1"))); // newest won!
        assertEquals("val2", bufferToString(compactedReader.get("k2")));
        assertEquals("val3", bufferToString(compactedReader.get("k3")));

        // Index and footer verification
        assertEquals(3, compactedReader.getFooter().entryCount());
        assertEquals(3, compactedReader.getIndex().size());
    }

    @Test
    @DisplayName("Should remove tombstones and old values completely during compaction")
    void shouldDropTombstonesAndReclaimSpace(@TempDir Path tempDir) throws IOException {
        // Table 1 (newer, rank 0): "deletedKey" is deleted, "user2" is active
        SSTableReader r1 = createSSTable(tempDir.resolve("000002.sst"), List.of(
                TestData.delete("deletedKey"),
                TestData.put("user2", "active")
        ));

        // Table 2 (older, rank 1): "deletedKey" was present, "user1" was present
        SSTableReader r2 = createSSTable(tempDir.resolve("000001.sst"), List.of(
                TestData.put("deletedKey", "oldData"),
                TestData.put("user1", "alice")
        ));

        Path compactedPath = tempDir.resolve("compacted.sst");
        SSTableReader compactedReader = compactor.compact(List.of(r1, r2), compactedPath);
        openReaders.add(compactedReader);

        // deletedKey should NOT exist at all (not even in index!)
        assertFalse(compactedReader.containsKey("deletedKey"));
        assertNull(compactedReader.get("deletedKey"));

        // Remaining keys are accessible
        assertEquals("alice", bufferToString(compactedReader.get("user1")));
        assertEquals("active", bufferToString(compactedReader.get("user2")));

        // Exactly 2 entries in total
        assertEquals(2, compactedReader.getFooter().entryCount());
        assertEquals(2, compactedReader.getIndex().size());
    }

    @Test
    @DisplayName("Should preserve lexicographical ordering across multiple SSTables")
    void shouldPreserveKeyOrdering(@TempDir Path tempDir) throws IOException {
        // Three tables with interleaved keys
        SSTableReader r1 = createSSTable(tempDir.resolve("000003.sst"), List.of(
                TestData.put("c", "3"),
                TestData.put("e", "5")
        ));
        SSTableReader r2 = createSSTable(tempDir.resolve("000002.sst"), List.of(
                TestData.put("b", "2"),
                TestData.put("f", "6")
        ));
        SSTableReader r3 = createSSTable(tempDir.resolve("000001.sst"), List.of(
                TestData.put("a", "1"),
                TestData.put("d", "4")
        ));

        Path compactedPath = tempDir.resolve("compacted.sst");
        SSTableReader compactedReader = compactor.compact(List.of(r1, r2, r3), compactedPath);
        openReaders.add(compactedReader);

        List<String> keys = new ArrayList<>();
        for (SSTableEntry entry : compactedReader) {
            keys.add(entry.key());
        }

        assertEquals(List.of("a", "b", "c", "d", "e", "f"), keys);
    }
}
