package com.ibm.internship.lsm.sstable;

import com.ibm.internship.lsm.memtable.MemTable;
import com.ibm.internship.lsm.wal.WalEncoder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SSTableWriter implements AutoCloseable {
    private final FileChannel channel;
    private final Path sstablePath;
    private long currentOffset = 0;
    private record IndexEntry(String key, long offset, int recordLength) {}
    private final List<IndexEntry> indexEntryList = new ArrayList<>();

    public SSTableWriter(Path path) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        this.sstablePath = path;
    }

    public void append(String key, byte[] value, boolean isDelete) throws IOException {
        ByteBuffer buffer = WalEncoder.encode(key, value, isDelete);

        int recordLength = buffer.remaining();
        long offset = currentOffset;

        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }

        currentOffset += recordLength;
        indexEntryList.add(new IndexEntry(key, offset, recordLength));
    }

    public void finish() throws IOException {
        long indexOffset = currentOffset;

        for (IndexEntry indexEntry : indexEntryList) {
            byte[] keyBytes = indexEntry.key.getBytes(StandardCharsets.UTF_8);
            int keyLength = keyBytes.length;

            ByteBuffer buffer = ByteBuffer.allocate(16 + keyLength);

            buffer.putInt(keyLength);
            buffer.put(keyBytes);
            buffer.putLong(indexEntry.offset);
            buffer.putInt(indexEntry.recordLength);

            buffer.flip();
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }

        int indexLength = (int) (channel.position() - indexOffset);

        ByteBuffer footer = ByteBuffer.allocate(24);

        int entryCount = indexEntryList.size();
        long magicNumber = 0x4D494E494C534D31L;

        footer.putLong(indexOffset);
        footer.putInt(indexLength);
        footer.putInt(entryCount);
        footer.putLong(magicNumber);

        footer.flip();
        while (footer.hasRemaining()) {
            channel.write(footer);
        }

        channel.force(false);
    }

    public void writeFromMemTable(MemTable memTable) throws IOException {
        for (Map.Entry<String, MemTable.Entry> entry : memTable.entrySet()) {
            MemTable.Entry entryValue = entry.getValue();
            byte[] valueBytes = null;

            if (entryValue.getValue() != null && !entryValue.isTombstone()) {
                ByteBuffer buffer = entryValue.getValue().duplicate();
                valueBytes = new byte[buffer.remaining()];
                buffer.get(valueBytes);
            }

            this.append(entry.getKey(), valueBytes, entryValue.isTombstone());
        }
    }

    @Override
    public void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.force(true);
            channel.close();
        }
    }
}
