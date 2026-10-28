package com.ibm.internship.lsm.memtable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class MemTableTest {

    @Test
    @DisplayName("Should put and retrieve entries successfully as ByteBuffer")
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

        ByteBuffer retrieved = memTable.get("user:1");
        assertNotNull(retrieved);
        assertEquals(ByteBuffer.wrap(value), retrieved);
    }

    @Test
    @DisplayName("Should return ReadOnlyBuffer that throws ReadOnlyBufferException on modification")
    void shouldReturnReadOnlyBufferPreventingModification() {
        MemTable memTable = new MemTable();
        byte[] value = new byte[]{1, 2, 3};
        memTable.put("user:1", value);

        ByteBuffer retrieved = memTable.get("user:1");
        assertNotNull(retrieved);

        // Attempting to mutate returned buffer must throw ReadOnlyBufferException
        assertThrows(ReadOnlyBufferException.class, () -> retrieved.put(0, (byte) 99));

        // Stored value remains intact
        assertEquals((byte) 1, memTable.get("user:1").get(0));
    }

    @Test
    @DisplayName("Should handle tombstones and deletes correctly")
    void shouldHandleTombstonesAndDeletes() {
        MemTable memTable = new MemTable();
        memTable.put("user:1", "value1".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, memTable.entryCount());

        memTable.delete("user:1");

        assertEquals(0, memTable.entryCount());
        assertEquals(1, memTable.allEntryCount());
        assertNull(memTable.get("user:1"));
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
        assertTrue(memTable.getBytesSize() > sizeAfterFirst);

        memTable.delete("key1");
        assertEquals(0, memTable.entryCount());
        assertTrue(memTable.getBytesSize() > 0);
    }
}