package com.ibm.internship.lsm.wal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WalTest {
    @Test
    @DisplayName("Should write entries to file and track size")
    void shouldAppendAndTrackSize(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");
        try (Wal wal = new Wal(walPath)) {
            assertEquals(0, wal.size());
            wal.append("user:1", "value1".getBytes(StandardCharsets.UTF_8), false);
            assertTrue(wal.size() > 0);
            long sizeBeforeDelete = wal.size();
            wal.append("user:1", null, true);
            assertTrue(wal.size() > sizeBeforeDelete);
        }
    }
    @Test
    @DisplayName("Should reset wal to zero size")
    void shouldReset(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");
        try (Wal wal = new Wal(walPath)) {
            wal.append("key", "value".getBytes(StandardCharsets.UTF_8), false);
            assertTrue(wal.size() > 0);
            wal.reset();
            assertEquals(0, wal.size());
            assertEquals(0, Files.size(walPath));
        }
    }
}
