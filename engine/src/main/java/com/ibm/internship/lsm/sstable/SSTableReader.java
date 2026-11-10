package com.ibm.internship.lsm.sstable;

import com.ibm.internship.lsm.wal.OpType;
import com.ibm.internship.lsm.wal.WalDecoder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public class SSTableReader implements AutoCloseable, Iterable<SSTableEntry> {
    private final Path path;
    private final FileChannel channel;
    private final SSTableFooter footer;
    private final SSTableIndex index;
    private final BloomFilter filter;

    public SSTableReader(Path path) throws IOException {
        this.path = Objects.requireNonNull(path, "path cannot be null");
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            this.footer = SSTableFooter.read(this.channel);
            this.filter = readBloomFilter(this.channel, this.footer);
            this.index = SSTableIndex.read(this.channel, this.footer);
        } catch (Throwable t) {
            try {
                this.channel.close();
            } catch (IOException closeEx) {
                t.addSuppressed(closeEx);
            }
            throw t;
        }
    }

    public static SSTableReader open(Path path) throws IOException {
        return new SSTableReader(path);
    }

    public ByteBuffer get(String key) throws IOException {
        WalDecoder.DecodedRecord record = readRecord(key);
        if (record == null || record.getOpType() == OpType.DELETE) {
            return null;
        }

        return ByteBuffer.wrap(record.getValueBytes()).asReadOnlyBuffer();
    }

    public WalDecoder.DecodedRecord readRecord(String key) throws IOException {
        if (key == null) {
            return null;
        }

        if (filter != null && !filter.mightContain(key)) {
            return null;
        }

        IndexEntry entry = index.find(key);
        if (entry == null) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.allocate(entry.recordLength());
        long readOffset = entry.offset();

        while (buffer.hasRemaining()) {
            int bytesRead = channel.read(buffer, readOffset);
            if (bytesRead == -1) {
                throw new IOException(String.format(
                        "Unexpected EOF while reading SSTable record for key '%s' at offset %d",
                        key, readOffset
                ));
            }
            readOffset += bytesRead;
        }

        buffer.flip();
        WalDecoder.DecodedRecord record = WalDecoder.decode(buffer);

        if (!key.equals(record.getKey())) {
            throw new IOException(String.format(
                    "Corrupted SSTable record: index key '%s' does not match payload key '%s'",
                    key, record.getKey()
            ));
        }

        return record;
    }

    public boolean containsKey(String key) {
        if (key == null) {
            return false;
        }

        if (filter != null && !filter.mightContain(key)) {
            return false;
        }

        return index.find(key) != null;
    }

    @Override
    public SSTableIterator iterator() {
        return new SSTableIterator(channel, index);
    }

    public SSTableIterator iterator(String fromKey) {
        int startIndex = index.findLowerBoundIndex(fromKey);
        return new SSTableIterator(channel, index, startIndex);
    }

    public SSTableFooter getFooter() {
        return footer;
    }

    public SSTableIndex getIndex() {
        return index;
    }

    public Path getPath() {
        return path;
    }

    public long getFileSize() throws IOException {
        return channel.size();
    }

    FileChannel getChannel() {
        return channel;
    }

    public BloomFilter getBloomFilter() {
        return filter;
    }

    private static BloomFilter readBloomFilter(FileChannel channel, SSTableFooter footer) throws IOException {
        if (footer.filterLength() == 0) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.allocate(footer.filterLength());
        long offset = footer.filterOffset();

        while (buffer.hasRemaining()) {
            int bytesRead = channel.read(buffer, offset);
            if (bytesRead == -1) {
                throw new IOException("Unexpected EOF while reading BloomFilter at offset " + offset);
            }
            offset += bytesRead;
        }

        buffer.flip();
        byte[] filterBytes = new byte[buffer.remaining()];
        buffer.get(filterBytes);

        return BloomFilter.fromByteArray(filterBytes);
    }

    @Override
    public void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }
}
