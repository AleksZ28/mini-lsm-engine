package com.ibm.internship.lsm.sstable;

import com.ibm.internship.lsm.wal.OpType;
import com.ibm.internship.lsm.wal.WalDecoder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

public class SSTableIterator implements Iterator<SSTableEntry> {
    private final FileChannel channel;
    private final SSTableIndex index;
    private int currentIndex;

    public SSTableIterator(FileChannel channel, SSTableIndex index) {
        this(channel, index, 0);
    }

    public SSTableIterator(FileChannel channel, SSTableIndex index, int startIndex) {
        this.channel = Objects.requireNonNull(channel, "channel cannot be null");
        this.index = Objects.requireNonNull(index, "index cannot be null");
        if (startIndex < 0 || startIndex > index.size()) {
            throw new IndexOutOfBoundsException("startIndex: " + startIndex + ", size: " + index.size());
        }
        this.currentIndex = startIndex;
    }

    @Override
    public boolean hasNext() {
        return currentIndex < index.size();
    }

    @Override
    public SSTableEntry next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        IndexEntry entry = index.get(currentIndex++);
        try {
            ByteBuffer buffer = ByteBuffer.allocate(entry.recordLength());
            long readOffset = entry.offset();

            while (buffer.hasRemaining()) {
                int bytesRead = channel.read(buffer, readOffset);
                if (bytesRead == -1) {
                    throw new IOException(String.format(
                            "Unexpected EOF while reading SSTable record for key '%s' at offset %d",
                            entry.key(), readOffset
                    ));
                }
                readOffset += bytesRead;
            }

            buffer.flip();
            WalDecoder.DecodedRecord record = WalDecoder.decode(buffer);

            if (!entry.key().equals(record.getKey())) {
                throw new IOException(String.format(
                        "Corrupted SSTable record: index key '%s' does not match payload key '%s'",
                        entry.key(), record.getKey()
                ));
            }

            ByteBuffer valueBuffer = record.getOpType() == OpType.DELETE
                    ? null
                    : ByteBuffer.wrap(record.getValueBytes()).asReadOnlyBuffer();

            return new SSTableEntry(record.getKey(), valueBuffer, record.getOpType());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
