package com.ibm.internship.lsm.wal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class WalDecoderTest {

    @Test
    @DisplayName("Should decode PUT record encoded by WalEncoder")
    void shouldDecodePutRecord() throws IOException {
        String key = "user:42";
        byte[] value = "John Doe".getBytes(StandardCharsets.UTF_8);

        ByteBuffer encoded = WalEncoder.encode(key, value, false);
        WalDecoder.DecodedRecord record = WalDecoder.decode(encoded);

        assertNotNull(record);
        assertEquals(OpType.PUT, record.getOpType());
        assertEquals(key, record.getKey());
        assertArrayEquals(value, record.getValueBytes());
    }

    @Test
    @DisplayName("Should decode DELETE record encoded by WalEncoder")
    void shouldDecodeDeleteRecord() throws IOException {
        String key = "session:xyz";

        ByteBuffer encoded = WalEncoder.encode(key, null, true);
        WalDecoder.DecodedRecord record = WalDecoder.decode(encoded);

        assertNotNull(record);
        assertEquals(OpType.DELETE, record.getOpType());
        assertEquals(key, record.getKey());
        assertEquals(0, record.getValueBytes().length);
    }

    @Test
    @DisplayName("Should decode record from separate header and payload buffers")
    void shouldDecodeFromSeparateBuffers() throws IOException {
        String key = "config:timeout";
        byte[] value = new byte[]{0, 0, 1, 0};

        ByteBuffer encoded = WalEncoder.encode(key, value, false);

        ByteBuffer header = ByteBuffer.allocate(WalDecoder.HEADER_SIZE);
        encoded.get(header.array());
        header.position(WalDecoder.HEADER_SIZE);
        header.flip();

        ByteBuffer payload = ByteBuffer.allocate(encoded.remaining());
        encoded.get(payload.array());
        payload.position(payload.capacity());
        payload.flip();

        WalDecoder.DecodedRecord record = WalDecoder.decode(header, payload);

        assertNotNull(record);
        assertEquals(OpType.PUT, record.getOpType());
        assertEquals(key, record.getKey());
        assertArrayEquals(value, record.getValueBytes());
    }

    @Test
    @DisplayName("Should correctly handle multi-byte UTF-8 keys")
    void shouldHandleMultiByteUtf8Keys() throws IOException {
        String polishKey = "zażółć_gęślą_jaźń";
        byte[] value = "sample".getBytes(StandardCharsets.UTF_8);

        ByteBuffer encoded = WalEncoder.encode(polishKey, value, false);
        WalDecoder.DecodedRecord record = WalDecoder.decode(encoded);

        assertEquals(polishKey, record.getKey());
        assertArrayEquals(value, record.getValueBytes());
    }

    @Test
    @DisplayName("Should throw IOException on corrupted checksum")
    void shouldThrowOnCorruptedChecksum() {
        ByteBuffer encoded = WalEncoder.encode("k1", "v1".getBytes(StandardCharsets.UTF_8), false);

        // Corrupt the payload byte
        byte[] raw = encoded.array();
        raw[raw.length - 1] = (byte) (raw[raw.length - 1] ^ 0xFF);

        assertThrows(IOException.class, () -> WalDecoder.decode(encoded));
    }

    @Test
    @DisplayName("Should throw IOException when buffer has fewer bytes than header size")
    void shouldThrowOnTruncatedHeader() {
        ByteBuffer partialHeader = ByteBuffer.allocate(5);
        assertThrows(IOException.class, () -> WalDecoder.decode(partialHeader));
    }

    @Test
    @DisplayName("Should throw IOException when payload is truncated")
    void shouldThrowOnTruncatedPayload() {
        ByteBuffer encoded = WalEncoder.encode("long_key", "payload_value".getBytes(StandardCharsets.UTF_8), false);
        // Truncate limit to be less than full record
        encoded.limit(encoded.limit() - 4);

        assertThrows(IOException.class, () -> WalDecoder.decode(encoded));
    }
}
