package com.ibm.internship.lsm.integration;

import com.ibm.internship.lsm.core.MiniLsmEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReadWritePipelineIntegrationTest {

    private static byte[] bytes(String str) {
        return str.getBytes(StandardCharsets.UTF_8);
    }

    private void assertValue(MiniLsmEngine engine, String key, String expectedVal) throws IOException {
        ByteBuffer actual = engine.get(key);
        assertNotNull(actual, "Key '" + key + "' should be present");
        assertEquals(ByteBuffer.wrap(bytes(expectedVal)), actual, "Value mismatch for key '" + key + "'");
    }

    @Test
    @DisplayName("Complete Read/Write Pipeline Lifecycle (MemTable -> Flush -> SSTables -> Compaction -> Crash Recovery)")
    void shouldExecuteFullDataLifecycleThroughReadWritePipeline(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("pipeline.wal");
        Path sstDir = tempDir.resolve("sstables");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, sstDir)) {
            engine.put("user:1", bytes("Alice_v1"));
            engine.put("user:2", bytes("Bob_v1"));

            assertValue(engine, "user:1", "Alice_v1");
            assertValue(engine, "user:2", "Bob_v1");

            assertEquals(0, engine.getSstables().size(), "No SSTables should exist before first flush");
            assertTrue(Files.size(walPath) > 0, "WAL must contain unpersisted writes");


            engine.flush();

            assertEquals(1, engine.getSstables().size(), "First flush must create 1 SSTable");
            assertTrue(Files.exists(sstDir.resolve("1.sst")), "1.sst must exist on disk");
            assertEquals(0, Files.size(walPath), "Active WAL must be truncated to 0 after flush");

            assertValue(engine, "user:1", "Alice_v1");
            assertValue(engine, "user:2", "Bob_v1");


            engine.put("user:1", bytes("Alice_v2"));

            assertValue(engine, "user:1", "Alice_v2");
            assertValue(engine, "user:2", "Bob_v1");

            engine.delete("user:2");

            assertNull(engine.get("user:2"), "Tombstone in MemTable must hide older value in 1.sst");
            assertValue(engine, "user:1", "Alice_v2");

            engine.put("user:3", bytes("Charlie_v1"));
            engine.flush();

            assertEquals(2, engine.getSstables().size(), "Two SSTables should exist on disk");
            assertTrue(Files.exists(sstDir.resolve("2.sst")), "2.sst must exist on disk");

            assertValue(engine, "user:1", "Alice_v2");
            assertNull(engine.get("user:2"), "Tombstone in 2.sst must mask old value in 1.sst");
            assertValue(engine, "user:3", "Charlie_v1");


            engine.compact();

            assertEquals(1, engine.getSstables().size(), "Only 1 compacted SSTable should remain");
            assertTrue(Files.exists(sstDir.resolve("3.sst")), "Compacted 3.sst must exist");
            assertFalse(Files.exists(sstDir.resolve("1.sst")), "1.sst must be deleted after compaction");
            assertFalse(Files.exists(sstDir.resolve("2.sst")), "2.sst must be deleted after compaction");

            assertValue(engine, "user:1", "Alice_v2");
            assertNull(engine.get("user:2"), "Purged tombstone must return null");
            assertValue(engine, "user:3", "Charlie_v1");

            assertEquals(2, engine.getSstables().getFirst().getFooter().entryCount(),
                    "Compacted SSTable must only contain 2 active entries (user:1 and user:3)");


            // For crash recovery
            engine.put("user:4", bytes("Diana_v1"));
        }

        // Crash recovery
        try (MiniLsmEngine restartedEngine = new MiniLsmEngine(walPath, sstDir)) {
            assertEquals(1, restartedEngine.getSstables().size(), "Restarted engine should load 3.sst");
            restartedEngine.recoverFromWal();

            assertValue(restartedEngine, "user:1", "Alice_v2");
            assertNull(restartedEngine.get("user:2"));
            assertValue(restartedEngine, "user:3", "Charlie_v1");
            assertValue(restartedEngine, "user:4", "Diana_v1");
        }
    }
}
