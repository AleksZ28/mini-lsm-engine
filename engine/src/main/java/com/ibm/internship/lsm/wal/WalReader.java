package com.ibm.internship.lsm.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

public class WalReader implements AutoCloseable {
    private final FileChannel channel;

    public WalReader(Path path) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
    }

    public static class DecodedRecord {
        final OpType opType;
        final String key;
        final byte[] valueBytes;

        DecodedRecord(OpType opType, String key, byte[] valueBytes) {
            this.opType = opType;
            this.key = key;
            this.valueBytes = valueBytes;
        }

        public OpType getOpType() {
            return opType;
        }

        public String getKey() {
            return key;
        }

        public byte[] getValueBytes() {
            return valueBytes;
        }
    }

    public DecodedRecord readNextRecord() throws IOException {
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
        int storedChecksum = headerBuffer.getInt();
        byte opTypeCode = headerBuffer.get();
        int keyLength = headerBuffer.getInt();
        int valueLength = headerBuffer.getInt();

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

        byte[] keyBytes = new byte[keyLength];
        payloadBuffer.get(keyBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);

        byte[] valueBytes = new byte[valueLength];
        payloadBuffer.get(valueBytes);

        CRC32 crc = new CRC32();
        crc.update(headerBuffer.array(), 4, WalEncoder.HEADER_SIZE - 4);
        crc.update(payloadBuffer.array(), 0, payloadBuffer.capacity());
        long calculatedChecksum = crc.getValue();

        if ((int) calculatedChecksum != storedChecksum) {
            throw new IOException("WAL corruption: Checksum mismatch. Expected " + storedChecksum + ", but calculated " + (int) calculatedChecksum);
        }

        OpType opType = OpType.fromCode(opTypeCode);
        return new DecodedRecord(opType, key, valueBytes);
    }

    @Override
    public void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }
}
