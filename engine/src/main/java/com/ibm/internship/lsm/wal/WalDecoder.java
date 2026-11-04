package com.ibm.internship.lsm.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

public class WalDecoder {
    public static final int HEADER_SIZE = WalEncoder.HEADER_SIZE;

    public static class DecodedRecord {
        private final OpType opType;
        private final String key;
        private final byte[] valueBytes;

        public DecodedRecord(OpType opType, String key, byte[] valueBytes) {
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

    public static DecodedRecord decode(ByteBuffer buffer) throws IOException {
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer cannot be null");
        }
        if (buffer.remaining() < HEADER_SIZE) {
            throw new IOException("Truncated WAL record: expected at least " + HEADER_SIZE + " header bytes, but found " + buffer.remaining());
        }

        int startPos = buffer.position();
        int storedChecksum = buffer.getInt();
        byte opTypeCode = buffer.get();
        int keyLength = buffer.getInt();
        int valueLength = buffer.getInt();

        if (keyLength < 0 || valueLength < 0) {
            throw new IOException("Corrupted WAL record: invalid lengths (keyLength=" + keyLength + ", valueLength=" + valueLength + ")");
        }

        int payloadLength = keyLength + valueLength;
        if (buffer.remaining() < payloadLength) {
            throw new IOException("Truncated WAL record payload: expected " + payloadLength + " bytes, but found " + buffer.remaining());
        }

        CRC32 crc = new CRC32();
        if (buffer.hasArray()) {
            crc.update(buffer.array(), buffer.arrayOffset() + startPos + 4, 1 + 4 + 4 + payloadLength);
        } else {
            ByteBuffer dup = buffer.duplicate();
            dup.position(startPos + 4);
            dup.limit(startPos + 4 + 1 + 4 + 4 + payloadLength);
            crc.update(dup);
        }
        long calculatedChecksum = crc.getValue();

        if ((int) calculatedChecksum != storedChecksum) {
            throw new IOException("WAL corruption: Checksum mismatch. Expected " + storedChecksum + ", but calculated " + (int) calculatedChecksum);
        }

        byte[] keyBytes = new byte[keyLength];
        buffer.get(keyBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);

        byte[] valueBytes = new byte[valueLength];
        buffer.get(valueBytes);

        OpType opType = OpType.fromCode(opTypeCode);
        return new DecodedRecord(opType, key, valueBytes);
    }

    public static DecodedRecord decode(ByteBuffer headerBuffer, ByteBuffer payloadBuffer) throws IOException {
        if (headerBuffer == null || payloadBuffer == null) {
            throw new IllegalArgumentException("Buffers cannot be null");
        }
        if (headerBuffer.remaining() < HEADER_SIZE) {
            throw new IOException("Truncated WAL header: expected at least " + HEADER_SIZE + " bytes, but found " + headerBuffer.remaining());
        }

        int headerStart = headerBuffer.position();
        int storedChecksum = headerBuffer.getInt();
        byte opTypeCode = headerBuffer.get();
        int keyLength = headerBuffer.getInt();
        int valueLength = headerBuffer.getInt();

        if (keyLength < 0 || valueLength < 0) {
            throw new IOException("Corrupted WAL record: invalid lengths (keyLength=" + keyLength + ", valueLength=" + valueLength + ")");
        }

        if (payloadBuffer.remaining() < keyLength + valueLength) {
            throw new IOException("Truncated WAL payload: expected " + (keyLength + valueLength) + " bytes, but found " + payloadBuffer.remaining());
        }

        CRC32 crc = new CRC32();
        if (headerBuffer.hasArray()) {
            crc.update(headerBuffer.array(), headerBuffer.arrayOffset() + headerStart + 4, HEADER_SIZE - 4);
        } else {
            ByteBuffer dupHeader = headerBuffer.duplicate();
            dupHeader.position(headerStart + 4);
            dupHeader.limit(headerStart + HEADER_SIZE);
            crc.update(dupHeader);
        }

        if (payloadBuffer.hasArray()) {
            crc.update(payloadBuffer.array(), payloadBuffer.arrayOffset() + payloadBuffer.position(), keyLength + valueLength);
        } else {
            ByteBuffer dupPayload = payloadBuffer.duplicate();
            dupPayload.limit(payloadBuffer.position() + keyLength + valueLength);
            crc.update(dupPayload);
        }
        long calculatedChecksum = crc.getValue();

        if ((int) calculatedChecksum != storedChecksum) {
            throw new IOException("WAL corruption: Checksum mismatch. Expected " + storedChecksum + ", but calculated " + (int) calculatedChecksum);
        }

        byte[] keyBytes = new byte[keyLength];
        payloadBuffer.get(keyBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);

        byte[] valueBytes = new byte[valueLength];
        payloadBuffer.get(valueBytes);

        OpType opType = OpType.fromCode(opTypeCode);
        return new DecodedRecord(opType, key, valueBytes);
    }
}
