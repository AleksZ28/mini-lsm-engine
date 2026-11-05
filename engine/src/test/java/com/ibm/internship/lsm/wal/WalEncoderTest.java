package com.ibm.internship.lsm.wal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class WalEncoderTest {

    @Test
    @DisplayName("Should encode PUT record with exact 13-byte header and valid CRC32")
    void shouldEncodePutRecord() {
        String key = "user:42";
        byte[] value = "John Doe".getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = WalEncoder.encode(key, value, false);

        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int expectedTotalSize = WalEncoder.HEADER_SIZE + keyBytes.length + value.length;

        assertEquals(expectedTotalSize, buffer.limit());
        assertEquals(0, buffer.position());

        // 1. Checksum (Bytes 0..3)
        int storedCrc = buffer.getInt();

        // 2. OpType (Byte 4)
        byte opType = buffer.get();
        assertEquals(OpType.PUT.getCode(), opType);

        // 3. KeyLength (Bytes 5..8)
        int keyLength = buffer.getInt();
        assertEquals(keyBytes.length, keyLength);

        // 4. ValueLength (Bytes 9..12)
        int valueLength = buffer.getInt();
        assertEquals(value.length, valueLength);

        // 5. Payloads
        byte[] readKeyBytes = new byte[keyLength];
        buffer.get(readKeyBytes);
        assertArrayEquals(keyBytes, readKeyBytes);

        byte[] readValueBytes = new byte[valueLength];
        buffer.get(readValueBytes);
        assertArrayEquals(value, readValueBytes);

        // 6. Independent CRC verification (over bytes 4..end)
        byte[] rawArray = buffer.array();
        CRC32 independentCrc = new CRC32();
        independentCrc.update(rawArray, 4, expectedTotalSize - 4);

        assertEquals((int) independentCrc.getValue(), storedCrc, "Stored CRC32 must match recalculated checksum");
    }

    @Test
    @DisplayName("Should encode DELETE record with OpType 0x02 and zero-length value")
    void shouldEncodeDeleteRecord() {
        String key = "session:xyz";

        ByteBuffer buffer = WalEncoder.encode(key, null, true);

        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int expectedTotalSize = WalEncoder.HEADER_SIZE + keyBytes.length;

        assertEquals(expectedTotalSize, buffer.limit());

        int storedCrc = buffer.getInt();
        byte opType = buffer.get();
        assertEquals(OpType.DELETE.getCode(), opType);

        int keyLength = buffer.getInt();
        assertEquals(keyBytes.length, keyLength);

        int valueLength = buffer.getInt();
        assertEquals(0, valueLength, "DELETE record must have value length 0");

        byte[] readKeyBytes = new byte[keyLength];
        buffer.get(readKeyBytes);
        assertArrayEquals(keyBytes, readKeyBytes);

        // CRC verification
        CRC32 independentCrc = new CRC32();
        independentCrc.update(buffer.array(), 4, expectedTotalSize - 4);
        assertEquals((int) independentCrc.getValue(), storedCrc);
    }

    @Test
    @DisplayName("Should handle null or empty values on PUT without throwing exception")
    void shouldHandleNullAndEmptyValueOnPut() {
        ByteBuffer nullValBuffer = WalEncoder.encode("k1", null, false);
        assertEquals(WalEncoder.HEADER_SIZE + "k1".getBytes(StandardCharsets.UTF_8).length, nullValBuffer.limit());
        nullValBuffer.position(9);
        assertEquals(0, nullValBuffer.getInt(), "Null value on PUT should produce ValueLength 0");

        ByteBuffer emptyValBuffer = WalEncoder.encode("k2", new byte[0], false);
        assertEquals(WalEncoder.HEADER_SIZE + "k2".getBytes(StandardCharsets.UTF_8).length, emptyValBuffer.limit());
        emptyValBuffer.position(9);
        assertEquals(0, emptyValBuffer.getInt(), "Empty byte array value on PUT should produce ValueLength 0");
    }

    @Test
    @DisplayName("Should correctly handle multi-byte UTF-8 keys (e.g. Polish characters)")
    void shouldHandleMultiByteUtf8Keys() {
        String polishKey = "zażółć_gęślą_jaźń"; // 17 chars, but 29 bytes in UTF-8
        byte[] polishKeyBytes = polishKey.getBytes(StandardCharsets.UTF_8);
        assertTrue(polishKeyBytes.length > polishKey.length(), "UTF-8 byte length should exceed character length");

        ByteBuffer buffer = WalEncoder.encode(polishKey, new byte[]{1, 2, 3}, false);

        buffer.position(5);
        int keyLength = buffer.getInt();
        assertEquals(polishKeyBytes.length, keyLength, "Key length must be UTF-8 byte length, not char length");
    }

    @Test
    @DisplayName("Corrupting payload byte should produce different CRC32")
    void shouldDetectPayloadCorruption() {
        ByteBuffer buffer = WalEncoder.encode("key", "val".getBytes(StandardCharsets.UTF_8), false);
        int originalCrc = buffer.getInt(0);

        byte[] raw = buffer.array().clone();
        // Corrupt 1 byte in payload (e.g., last byte)
        raw[raw.length - 1] = (byte) (raw[raw.length - 1] ^ 0xFF);

        CRC32 corruptedCrc = new CRC32();
        corruptedCrc.update(raw, 4, raw.length - 4);

        assertNotEquals(originalCrc, (int) corruptedCrc.getValue(), "Corrupted data must yield different checksum");
    }
}
