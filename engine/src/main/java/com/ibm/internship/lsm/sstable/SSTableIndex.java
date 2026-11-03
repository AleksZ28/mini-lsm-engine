package com.ibm.internship.lsm.sstable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * In-memory index loaded from the SSTable Index Block.
 * Entries are sorted lexicographically by key, supporting O(log N) binary search.
 */
public class SSTableIndex {
    private final List<IndexEntry> entries;

    public SSTableIndex(List<IndexEntry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(entries, "entries cannot be null")));
    }

    /**
     * Reads and parses the Index Block from the specified channel using metadata from the footer.
     *
     * @param channel Open readable FileChannel
     * @param footer  Validated SSTableFooter containing indexOffset, indexLength, and entryCount
     * @return Validated in-memory SSTableIndex
     * @throws IOException If reading fails or the index block is corrupted
     */
    public static SSTableIndex read(FileChannel channel, SSTableFooter footer) throws IOException {
        Objects.requireNonNull(channel, "channel cannot be null");
        Objects.requireNonNull(footer, "footer cannot be null");

        if (footer.entryCount() == 0) {
            return new SSTableIndex(Collections.emptyList());
        }

        ByteBuffer buffer = ByteBuffer.allocate(footer.indexLength());
        channel.position(footer.indexOffset());

        while (buffer.hasRemaining()) {
            int bytesRead = channel.read(buffer);
            if (bytesRead == -1) {
                throw new IOException("Unexpected EOF while reading SSTable index block at position " + channel.position());
            }
        }

        buffer.flip();
        return decode(buffer, footer);
    }

    /**
     * Decodes and validates index entries from a ByteBuffer.
     *
     * @param buffer ByteBuffer containing the exact bytes of the Index Block
     * @param footer SSTableFooter containing index boundary constraints
     * @return Validated SSTableIndex
     * @throws IOException If structure, ordering, or boundaries are invalid
     */
    public static SSTableIndex decode(ByteBuffer buffer, SSTableFooter footer) throws IOException {
        Objects.requireNonNull(buffer, "buffer cannot be null");
        Objects.requireNonNull(footer, "footer cannot be null");

        int expectedCount = footer.entryCount();
        List<IndexEntry> entries = new ArrayList<>(expectedCount);
        String previousKey = null;

        for (int i = 0; i < expectedCount; i++) {
            if (buffer.remaining() < 4) {
                throw new IOException("Truncated index entry at index " + i + ": expected key length");
            }

            int keyLength = buffer.getInt();
            if (keyLength < 0) {
                throw new IOException("Corrupted index entry at index " + i + ": negative key length " + keyLength);
            }

            // Must have keyLength bytes + 8 bytes (offset) + 4 bytes (recordLength)
            if (buffer.remaining() < keyLength + 12) {
                throw new IOException("Truncated index entry at index " + i + ": expected at least "
                        + (keyLength + 12) + " remaining bytes, but found " + buffer.remaining());
            }

            byte[] keyBytes = new byte[keyLength];
            buffer.get(keyBytes);
            String key = new String(keyBytes, StandardCharsets.UTF_8);

            long offset = buffer.getLong();
            int recordLength = buffer.getInt();

            if (offset < 0) {
                throw new IOException("Corrupted index entry at index " + i + ": negative offset " + offset);
            }
            if (recordLength < IndexEntry.MIN_RECORD_LENGTH) {
                throw new IOException("Corrupted index entry at index " + i + ": invalid recordLength " + recordLength);
            }

            // Sanity check: data record must start and end before the Index Block begins
            if (offset + (long) recordLength > footer.indexOffset()) {
                throw new IOException(String.format(
                        "Corrupted index entry at index %d ('%s'): record [%d, %d) extends beyond data section (indexOffset=%d)",
                        i, key, offset, offset + (long) recordLength, footer.indexOffset()
                ));
            }

            // Keys must be in strictly ascending order
            if (previousKey != null && key.compareTo(previousKey) <= 0) {
                throw new IOException(String.format(
                        "Corrupted index ordering: key '%s' at index %d is not strictly greater than previous key '%s'",
                        key, i, previousKey
                ));
            }

            entries.add(new IndexEntry(key, offset, recordLength));
            previousKey = key;
        }

        if (buffer.hasRemaining()) {
            throw new IOException("Corrupted index block: " + buffer.remaining() + " unparsed trailing bytes found");
        }

        return new SSTableIndex(entries);
    }

    /**
     * Performs an O(log N) binary search for the given key.
     *
     * @param key Key to find
     * @return Matching IndexEntry, or null if key does not exist in the index
     */
    public IndexEntry find(String key) {
        if (key == null || entries.isEmpty()) {
            return null;
        }

        int low = 0;
        int high = entries.size() - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            IndexEntry midVal = entries.get(mid);
            int cmp = midVal.key().compareTo(key);

            if (cmp < 0) {
                low = mid + 1;
            } else if (cmp > 0) {
                high = mid - 1;
            } else {
                return midVal;
            }
        }

        return null;
    }

    public List<IndexEntry> getEntries() {
        return entries;
    }

    public IndexEntry get(int index) {
        return entries.get(index);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
