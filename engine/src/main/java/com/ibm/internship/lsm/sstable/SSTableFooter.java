package com.ibm.internship.lsm.sstable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

public record SSTableFooter(long filterOffset, int filterLength, long indexOffset, int indexLength, int entryCount) {
    public static final int FOOTER_SIZE = 36;
    public static final long MAGIC_NUMBER = 0x4D494E494C534D31L;

    public SSTableFooter {
        if (filterOffset < 0) {
            throw new IllegalArgumentException("filterOffset cannot be negative: " + filterOffset);
        }
        if (filterLength < 0) {
            throw new IllegalArgumentException("filterLength cannot be negative: " + filterLength);
        }
        if (indexOffset < 0) {
            throw new IllegalArgumentException("indexOffset cannot be negative: " + indexOffset);
        }
        if (indexLength < 0) {
            throw new IllegalArgumentException("indexLength cannot be negative: " + indexLength);
        }
        if (entryCount < 0) {
            throw new IllegalArgumentException("entryCount cannot be negative: " + entryCount);
        }
    }

    public SSTableFooter(long indexOffset, int indexLength, int entryCount) {
        this(0, 0, indexOffset, indexLength, entryCount);
    }

    public static SSTableFooter read(FileChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel cannot be null");
        long fileSize = channel.size();

        if (fileSize < FOOTER_SIZE) {
            throw new IOException("Truncated SSTable: file size (" + fileSize + " bytes) is smaller than footer size (" + FOOTER_SIZE + " bytes)");
        }

        ByteBuffer footerBuffer = ByteBuffer.allocate(FOOTER_SIZE);
        long footerStart = fileSize - FOOTER_SIZE;
        channel.position(footerStart);

        while (footerBuffer.hasRemaining()) {
            int bytesRead = channel.read(footerBuffer);
            if (bytesRead == -1) {
                throw new IOException("Unexpected EOF while reading SSTable footer at position " + channel.position());
            }
        }

        footerBuffer.flip();
        return decode(footerBuffer, fileSize);
    }

    public static SSTableFooter decode(ByteBuffer buffer, long fileSize) throws IOException {
        Objects.requireNonNull(buffer, "buffer cannot be null");
        if (buffer.remaining() < FOOTER_SIZE) {
            throw new IOException("Truncated footer buffer: expected at least " + FOOTER_SIZE + " bytes, got " + buffer.remaining());
        }

        long filterOffset = buffer.getLong();
        int filterLength = buffer.getInt();
        long indexOffset = buffer.getLong();
        int indexLength = buffer.getInt();
        int entryCount = buffer.getInt();
        long magicNumber = buffer.getLong();

        if (magicNumber != MAGIC_NUMBER) {
            throw new IOException(String.format(
                    "Corrupted SSTable: magic number mismatch. Expected 0x%016X, but found 0x%016X",
                    MAGIC_NUMBER, magicNumber
            ));
        }

        if (filterOffset < 0 || filterLength < 0 || indexOffset < 0 || indexLength < 0 || entryCount < 0) {
            throw new IOException(String.format(
                    "Corrupted SSTable footer: invalid negative field (filterOffset=%d, filterLength=%d, indexOffset=%d, indexLength=%d, entryCount=%d)",
                    filterOffset, filterLength, indexOffset, indexLength, entryCount
            ));
        }

        long expectedIndexEnd = fileSize - FOOTER_SIZE;
        if (indexOffset + (long) indexLength != expectedIndexEnd) {
            throw new IOException(String.format(
                    "Corrupted SSTable footer: index section [%d, %d) does not align with footer start %d",
                    indexOffset, indexOffset + (long) indexLength, expectedIndexEnd
            ));
        }

        if (entryCount == 0 && indexLength != 0) {
            throw new IOException("Corrupted SSTable footer: entryCount is 0 but indexLength is " + indexLength);
        }
        if (entryCount > 0 && indexLength == 0) {
            throw new IOException("Corrupted SSTable footer: entryCount is " + entryCount + " but indexLength is 0");
        }

        return new SSTableFooter(filterOffset, filterLength, indexOffset, indexLength, entryCount);
    }
}
