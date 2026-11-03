package com.ibm.internship.lsm.sstable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

public class SSTableFooterTest {

    private void writeFooterFile(Path path, byte[] prefixData, long indexOffset, int indexLength, int entryCount, long magicNumber) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            if (prefixData != null && prefixData.length > 0) {
                channel.write(ByteBuffer.wrap(prefixData));
            }

            ByteBuffer footer = ByteBuffer.allocate(SSTableFooter.FOOTER_SIZE);
            footer.putLong(indexOffset);
            footer.putInt(indexLength);
            footer.putInt(entryCount);
            footer.putLong(magicNumber);
            footer.flip();

            while (footer.hasRemaining()) {
                channel.write(footer);
            }
        }
    }

    @Test
    @DisplayName("Should successfully parse a valid SSTable footer")
    void shouldParseValidFooter(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("valid.sst");

        // 3 valid index entries
        ByteBuffer indexBuf = ByteBuffer.allocate(3 * (4 + 4 + 8 + 4));
        for (int i = 1; i <= 3; i++) {
            byte[] kb = ("key" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            indexBuf.putInt(kb.length);
            indexBuf.put(kb);
            indexBuf.putLong((i - 1) * 20);
            indexBuf.putInt(20);
        }
        indexBuf.flip();
        byte[] indexBytes = new byte[indexBuf.remaining()];
        indexBuf.get(indexBytes);

        byte[] dummyData = new byte[60];
        long indexOffset = dummyData.length;
        int indexLength = indexBytes.length;
        int entryCount = 3;

        byte[] combined = new byte[dummyData.length + indexLength];
        System.arraycopy(dummyData, 0, combined, 0, dummyData.length);
        System.arraycopy(indexBytes, 0, combined, dummyData.length, indexLength);

        writeFooterFile(sstPath, combined, indexOffset, indexLength, entryCount, SSTableFooter.MAGIC_NUMBER);

        try (FileChannel channel = FileChannel.open(sstPath, StandardOpenOption.READ)) {
            SSTableFooter footer = SSTableFooter.read(channel);
            assertNotNull(footer);
            assertEquals(indexOffset, footer.indexOffset());
            assertEquals(indexLength, footer.indexLength());
            assertEquals(entryCount, footer.entryCount());
        }

        try (SSTableReader reader = SSTableReader.open(sstPath)) {
            assertEquals(indexOffset, reader.getFooter().indexOffset());
            assertEquals(indexLength, reader.getFooter().indexLength());
            assertEquals(entryCount, reader.getFooter().entryCount());
            assertEquals(combined.length + SSTableFooter.FOOTER_SIZE, reader.getFileSize());
            assertEquals(sstPath, reader.getPath());
        }
    }

    @Test
    @DisplayName("Should successfully parse empty SSTable footer")
    void shouldParseEmptySSTableFooter(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("empty.sst");
        writeFooterFile(sstPath, null, 0, 0, 0, SSTableFooter.MAGIC_NUMBER);

        try (SSTableReader reader = new SSTableReader(sstPath)) {
            SSTableFooter footer = reader.getFooter();
            assertEquals(0, footer.indexOffset());
            assertEquals(0, footer.indexLength());
            assertEquals(0, footer.entryCount());
            assertEquals(24, reader.getFileSize());
        }
    }

    @Test
    @DisplayName("Should throw IOException when file size is smaller than 24 bytes")
    void shouldThrowWhenFileSizeIsTooSmall(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("truncated.sst");
        Files.write(sstPath, new byte[12]); // only 12 bytes

        IOException ex = assertThrows(IOException.class, () -> SSTableReader.open(sstPath));
        assertTrue(ex.getMessage().contains("smaller than footer size"), ex.getMessage());
    }

    @Test
    @DisplayName("Should throw IOException when file is completely empty (0 bytes)")
    void shouldThrowWhenFileIsEmpty(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("empty_0bytes.sst");
        Files.createFile(sstPath);

        IOException ex = assertThrows(IOException.class, () -> SSTableReader.open(sstPath));
        assertTrue(ex.getMessage().contains("smaller than footer size"), ex.getMessage());
    }

    @Test
    @DisplayName("Should throw IOException when magic number does not match")
    void shouldThrowWhenMagicNumberIsCorrupted(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("bad_magic.sst");
        long invalidMagic = 0xDEADBEEFCAFEBABEL;
        writeFooterFile(sstPath, null, 0, 0, 0, invalidMagic);

        IOException ex = assertThrows(IOException.class, () -> SSTableReader.open(sstPath));
        assertTrue(ex.getMessage().contains("magic number mismatch"), ex.getMessage());
    }

    @Test
    @DisplayName("Should throw IOException when index section does not align with footer start")
    void shouldThrowWhenIndexBoundaryMisaligned(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("misaligned.sst");
        byte[] dummyData = new byte[100];
        writeFooterFile(sstPath, dummyData, 70, 40, 2, SSTableFooter.MAGIC_NUMBER);

        IOException ex = assertThrows(IOException.class, () -> SSTableReader.open(sstPath));
        assertTrue(ex.getMessage().contains("does not align with footer start"), ex.getMessage());
    }

    @Test
    @DisplayName("Should throw IOException when entryCount is positive but indexLength is zero")
    void shouldThrowWhenEntryCountPositiveButIndexLengthZero(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("inconsistent_index.sst");
        writeFooterFile(sstPath, null, 0, 0, 5, SSTableFooter.MAGIC_NUMBER);

        IOException ex = assertThrows(IOException.class, () -> SSTableReader.open(sstPath));
        assertTrue(ex.getMessage().contains("entryCount is 5 but indexLength is 0"), ex.getMessage());
    }

    @Test
    @DisplayName("Should throw IOException when entryCount is zero but indexLength is positive")
    void shouldThrowWhenEntryCountZeroButIndexLengthPositive(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("inconsistent_count.sst");
        byte[] dummyIndex = new byte[20];
        writeFooterFile(sstPath, dummyIndex, 0, 20, 0, SSTableFooter.MAGIC_NUMBER);

        IOException ex = assertThrows(IOException.class, () -> SSTableReader.open(sstPath));
        assertTrue(ex.getMessage().contains("entryCount is 0 but indexLength is 20"), ex.getMessage());
    }

    @Test
    @DisplayName("Should close file channel cleanly even if footer reading fails")
    void shouldCloseChannelOnFailure(@TempDir Path tempDir) throws IOException {
        Path sstPath = tempDir.resolve("fail_close.sst");
        writeFooterFile(sstPath, null, 0, 0, 0, 0x123456L); // invalid magic

        assertThrows(IOException.class, () -> new SSTableReader(sstPath));

        Files.delete(sstPath);
        assertFalse(Files.exists(sstPath));
    }
}
