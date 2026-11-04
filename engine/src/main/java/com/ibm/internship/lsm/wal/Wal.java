package com.ibm.internship.lsm.wal;

import com.ibm.internship.lsm.memtable.MemTable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public class Wal implements AutoCloseable {
    private final FileChannel channel;
    private final Path logPath;

    public Wal(Path path) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.channel.position(this.channel.size());
        this.logPath = path;
    }

    public void append(String key, byte[] value, boolean isDelete) throws IOException {
        ByteBuffer buffer = WalEncoder.encode(key, value, isDelete);

        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    public WalDecoder.DecodedRecord readNextRecord() throws IOException {
        ByteBuffer headerBuffer = ByteBuffer.allocate(WalEncoder.HEADER_SIZE);
        while (headerBuffer.hasRemaining()) {
            int bytesRead = channel.read(headerBuffer);
            if (bytesRead == -1) {
                break;
            }
        }

        if (headerBuffer.position() == 0) {
            return null;
        }
        if (headerBuffer.hasRemaining()) {
            throw new IOException("Truncated WAL header: expected " + WalEncoder.HEADER_SIZE + " bytes, but read " + headerBuffer.position());
        }

        headerBuffer.flip();
        int keyLength = headerBuffer.getInt(5);
        int valueLength = headerBuffer.getInt(9);

        if (keyLength < 0 || valueLength < 0) {
            throw new IOException("Corrupted WAL record: invalid lengths (keyLength=" + keyLength + ", valueLength=" + valueLength + ")");
        }

        ByteBuffer payloadBuffer = ByteBuffer.allocate(keyLength + valueLength);
        while (payloadBuffer.hasRemaining()) {
            int bytesRead = channel.read(payloadBuffer);
            if (bytesRead == -1) {
                throw new IOException("Truncated WAL payload: unexpected EOF before reading full record");
            }
        }
        payloadBuffer.flip();

        return WalDecoder.decode(headerBuffer, payloadBuffer);
    }

    public void recover(MemTable memTable) throws IOException {
        Objects.requireNonNull(memTable, "MemTable cannot be null");
        channel.position(0);

        long lastValidOffset = channel.position();

        while (true) {
            lastValidOffset = channel.position();
            WalDecoder.DecodedRecord record;

            try {
                record = readNextRecord();
            } catch (IOException e) {
                if (channel.position() == channel.size()) {
                    channel.truncate(lastValidOffset);
                    channel.position(lastValidOffset);
                    return;
                }
                throw e;
            }

            if (record == null) {
                break;
            }

            if (record.getOpType() == OpType.PUT) {
                memTable.put(record.getKey(), record.getValueBytes());
            } else if (record.getOpType() == OpType.DELETE) {
                memTable.delete(record.getKey());
            }
        }

        channel.position(channel.size());
    }

    public void reset() throws IOException {
        channel.truncate(0);
        channel.position(0);
    }

    public long size() throws IOException {
        return channel.size();
    }

    public void position(long newPosition) throws IOException {
        channel.position(newPosition);
    }

    public long position() throws IOException {
        return channel.position();
    }

    public Path getLogPath() {
        return logPath;
    }

    @Override
    public void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.force(true);
            channel.close();
        }
    }
}
