package com.ibm.internship.lsm.memtable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemTableTest {

    @Test
    @DisplayName("Should put and retrieve entries successfully")
    void shouldPutAndGetValues() {
        MemTable memTable = new MemTable();
        assertTrue(memTable.isEmpty());
        assertEquals(0, memTable.entryCount());

        byte[] value = "value1".getBytes(StandardCharsets.UTF_8);
        memTable.put("user:1", value);

        assertFalse(memTable.isEmpty());
        assertEquals(1, memTable.entryCount());
        assertEquals(1, memTable.allEntryCount());
        assertTrue(memTable.getBytesSize() > 0);

        byte[] retrieved = memTable.get("user:1");
        assertNotNull(retrieved);
        assertArrayEquals(value, retrieved);
    }

    @Test
    @DisplayName("Should handle tombstones and deletes correctly")
    void shouldHandleTombstonesAndDeletes() {
        MemTable memTable = new MemTable();
        memTable.put("user:1", "value1".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, memTable.entryCount());

        memTable.delete("user:1");

        assertEquals(0, memTable.entryCount(), "Active entry count should be 0 after delete");
        assertEquals(1, memTable.allEntryCount(), "All entry count should still be 1 (tombstone retained)");
        assertNull(memTable.get("user:1"), "Get on tombstone must return null");
    }

    @Test
    @DisplayName("Should track byte size and update on overwrites")
    void shouldTrackByteSizeAndOverwrites() {
        MemTable memTable = new MemTable();
        assertEquals(0, memTable.getBytesSize());

        memTable.put("key1", "val".getBytes(StandardCharsets.UTF_8));
        long sizeAfterFirst = memTable.getBytesSize();
        assertTrue(sizeAfterFirst > 0);

        memTable.put("key1", "longerValue".getBytes(StandardCharsets.UTF_8));
        assertTrue(memTable.getBytesSize() > sizeAfterFirst, "Overwriting with longer value should increase byteSize");

        memTable.delete("key1");
        assertEquals(0, memTable.entryCount());
        assertTrue(memTable.getBytesSize() > 0, "Tombstone still retains key size in memory");
    }

    @Test
    @DisplayName("Should decrease byteSize when overwriting with shorter value")
    void shouldDecreaseByteSizeWhenOverwritingWithShorterValue() {
        MemTable memTable = new MemTable();

        byte[] longVal = new byte[100];
        memTable.put("k", longVal);
        long initialSize = memTable.getBytesSize();

        byte[] shortVal = new byte[20];
        memTable.put("k", shortVal);
        long newSize = memTable.getBytesSize();

        assertEquals(initialSize - 80, newSize, "Size should decrease by exactly the difference in value lengths");
    }

    @Test
    @DisplayName("Should maintain lexicographical order in entrySet (essential for SSTables)")
    void shouldMaintainLexicographicalOrderInEntrySet() {
        MemTable memTable = new MemTable();

        memTable.put("banana", "1".getBytes(StandardCharsets.UTF_8));
        memTable.put("apple", "2".getBytes(StandardCharsets.UTF_8));
        memTable.put("cherry", "3".getBytes(StandardCharsets.UTF_8));
        memTable.put("date", "4".getBytes(StandardCharsets.UTF_8));

        List<String> keysInOrder = new ArrayList<>();
        for (Map.Entry<String, MemTable.Entry> entry : memTable.entrySet()) {
            keysInOrder.add(entry.getKey());
        }

        assertEquals(List.of("apple", "banana", "cherry", "date"), keysInOrder,
                "Keys must be sorted in ascending lexicographical order");
    }

    @Test
    @DisplayName("Should defensively clone values to prevent external mutation")
    void shouldDefensivelyCloneValues() {
        MemTable memTable = new MemTable();

        byte[] original = new byte[]{1, 2, 3};
        memTable.put("k1", original);

        // Mutate original array after put
        original[0] = 99;
        assertEquals(1, memTable.get("k1")[0], "MemTable value must not be affected by modifying original array");

        // Mutate array returned by get
        byte[] retrieved = memTable.get("k1");
        retrieved[0] = 77;
        assertEquals(1, memTable.get("k1")[0], "MemTable value must not be affected by modifying retrieved array");
    }

    @Test
    @DisplayName("Should reject null keys in put and delete, and return null on get(null)")
    void shouldHandleNullKeysAppropriately() {
        MemTable memTable = new MemTable();

        assertThrows(IllegalArgumentException.class, () -> memTable.put(null, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> memTable.delete(null));
        assertNull(memTable.get(null));
    }

    @Test
    @DisplayName("Should handle delete on non-existent key")
    void shouldHandleDeleteOnNonExistentKey() {
        MemTable memTable = new MemTable();

        memTable.delete("unknown");

        assertEquals(0, memTable.entryCount());
        assertEquals(1, memTable.allEntryCount());
        assertNull(memTable.get("unknown"));
        assertTrue(memTable.getBytesSize() > 0, "Tombstone for new key should account for key size");
    }

    @Test
    @DisplayName("Should return unmodifiable snapshot from entrySet")
    void shouldReturnUnmodifiableEntrySet() {
        MemTable memTable = new MemTable();
        memTable.put("k1", "v1".getBytes(StandardCharsets.UTF_8));

        assertThrows(UnsupportedOperationException.class, () -> memTable.entrySet().clear(),
                "Returned entrySet should be unmodifiable");
    }
}