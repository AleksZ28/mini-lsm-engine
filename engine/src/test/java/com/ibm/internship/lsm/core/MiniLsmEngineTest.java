package com.ibm.internship.lsm.core;

import com.ibm.internship.lsm.sstable.IndexEntry;
import com.ibm.internship.lsm.sstable.SSTableFooter;
import com.ibm.internship.lsm.sstable.SSTableReader;
import com.ibm.internship.lsm.wal.WalEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MiniLsmEngineTest {

    record TestRecord(String key, byte[] value, boolean isDelete) {}

    private SSTableReader createSSTableReader(Path path, List<TestRecord> records) throws IOException {
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

        return SSTableReader.open(path);
    }

    @Test
    @DisplayName("Should put and retrieve value successfully as ByteBuffer")
    void shouldPutAndGet(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
            byte[] value = "value123".getBytes(StandardCharsets.UTF_8);
            engine.put("user:1", value);

            ByteBuffer result = engine.get("user:1");
            assertNotNull(result, "Value should be present");
            assertEquals(ByteBuffer.wrap(value), result);
        }
    }

    @Test
    @DisplayName("Should return null for non-existent key")
    void shouldReturnNullForNonExistentKey(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
            ByteBuffer result = engine.get("missing:key");
            assertNull(result, "Non-existent key should return null");

            ByteBuffer nullResult = engine.get(null);
            assertNull(nullResult, "Null key should return null");
        }
    }

    @Test
    @DisplayName("Should delete key and make it unavailable")
    void shouldDeleteKey(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
            engine.put("user:1", "active".getBytes(StandardCharsets.UTF_8));
            assertNotNull(engine.get("user:1"));

            engine.delete("user:1");
            assertNull(engine.get("user:1"), "Deleted key should return null");
        }
    }

    @Test
    @DisplayName("Should reject null keys and values with IllegalArgumentException")
    void shouldRejectNulls(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
            assertThrows(IllegalArgumentException.class, () -> engine.put(null, new byte[]{1}));
            assertThrows(IllegalArgumentException.class, () -> engine.put("k", null));
            assertThrows(IllegalArgumentException.class, () -> engine.delete(null));
        }
    }

    @Test
    @DisplayName("Should recover state from WAL file after engine restart")
    void shouldRecoverFromWalAfterRestart(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("restart_test.wal");

        byte[] alice = "Alice".getBytes(StandardCharsets.UTF_8);
        byte[] bob = "Bob".getBytes(StandardCharsets.UTF_8);

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
            engine.put("user:1", alice);
            engine.put("user:2", bob);
            engine.delete("user:1");
        }

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
            engine.recoverFromWal();

            assertNull(engine.get("user:1"), "Deleted user:1 should remain deleted after recovery");
            ByteBuffer user2 = engine.get("user:2");
            assertNotNull(user2, "user:2 should be restored from WAL");
            assertEquals(ByteBuffer.wrap(bob), user2);

            byte[] charlie = "Charlie".getBytes(StandardCharsets.UTF_8);
            engine.put("user:3", charlie);
            ByteBuffer user3 = engine.get("user:3");
            assertNotNull(user3, "user:3 should be written");
            assertEquals(ByteBuffer.wrap(charlie), user3);
        }
    }

    @Test
    @DisplayName("Should retrieve value from SSTable when key is absent in MemTable")
    void shouldReadFromSSTableWhenNotInMemTable(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstPath = tempDir.resolve("00001.sst");
        byte[] sstValue = "sstable_val".getBytes(StandardCharsets.UTF_8);

        SSTableReader reader = createSSTableReader(sstPath, List.of(
                new TestRecord("disk:key", sstValue, false)
        ));

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, List.of(reader))) {
            ByteBuffer result = engine.get("disk:key");
            assertNotNull(result);
            assertEquals(ByteBuffer.wrap(sstValue), result);

            assertNull(engine.get("non:existent"));
        }
    }

    @Test
    @DisplayName("Should prioritize MemTable updates over on-disk SSTable data")
    void shouldPrioritizeMemTableOverSSTable(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstPath = tempDir.resolve("00001.sst");
        byte[] oldVal = "old_value".getBytes(StandardCharsets.UTF_8);
        byte[] newVal = "new_value".getBytes(StandardCharsets.UTF_8);

        SSTableReader reader = createSSTableReader(sstPath, List.of(
                new TestRecord("user:1", oldVal, false)
        ));

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, List.of(reader))) {
            assertEquals(ByteBuffer.wrap(oldVal), engine.get("user:1"));

            engine.put("user:1", newVal);
            assertEquals(ByteBuffer.wrap(newVal), engine.get("user:1"));
        }
    }

    @Test
    @DisplayName("Should respect MemTable tombstone and not resurrect older SSTable value")
    void shouldRespectMemTableTombstone(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstPath = tempDir.resolve("00001.sst");
        byte[] oldVal = "persisted_value".getBytes(StandardCharsets.UTF_8);

        SSTableReader reader = createSSTableReader(sstPath, List.of(
                new TestRecord("user:1", oldVal, false)
        ));

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath, List.of(reader))) {
            assertEquals(ByteBuffer.wrap(oldVal), engine.get("user:1"));

            engine.delete("user:1");
            assertNull(engine.get("user:1"));
        }
    }

    @Test
    @DisplayName("Should search SSTables from newest to oldest and respect SSTable tombstones")
    void shouldSearchMultipleSSTablesInOrder(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstOld = tempDir.resolve("00001.sst");
        Path sstNew = tempDir.resolve("00002.sst");

        byte[] k1Old = "k1_v1".getBytes(StandardCharsets.UTF_8);
        byte[] k1New = "k1_v2".getBytes(StandardCharsets.UTF_8);
        byte[] k2Val = "k2_live".getBytes(StandardCharsets.UTF_8);

        SSTableReader oldReader = createSSTableReader(sstOld, List.of(
                new TestRecord("k1", k1Old, false),
                new TestRecord("k2", k2Val, false)
        ));

        SSTableReader newReader = createSSTableReader(sstNew, List.of(
                new TestRecord("k1", k1New, false),
                new TestRecord("k2", null, true)
        ));

        try (MiniLsmEngine engine = new MiniLsmEngine(walPath)) {
            engine.addSSTable(oldReader);
            engine.addSSTable(newReader);

            assertEquals(2, engine.getSstables().size());
            assertEquals(ByteBuffer.wrap(k1New), engine.get("k1"));
            assertNull(engine.get("k2"));
        }
    }

    @Test
    @DisplayName("Should close all underlying SSTableReaders when engine closes")
    void shouldCloseSSTableReadersOnEngineClose(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("engine.wal");
        Path sstPath = tempDir.resolve("00001.sst");

        SSTableReader reader = createSSTableReader(sstPath, List.of(
                new TestRecord("k1", "v1".getBytes(StandardCharsets.UTF_8), false)
        ));

        MiniLsmEngine engine = new MiniLsmEngine(walPath, List.of(reader));
        assertNotNull(engine.get("k1"));

        engine.close();

        assertThrows(IOException.class, () -> reader.get("k1"));
    }
}
