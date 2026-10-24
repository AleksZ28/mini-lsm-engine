package com.ibm.internship.lsm.wal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

public class WalEncoder {
    public static final int HEADER_SIZE = 13;

    public static ByteBuffer encode(String key, String value, boolean isDelete)
    {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes;
        if (!isDelete && value != null && !value.isEmpty()) {
            valueBytes = value.getBytes(StandardCharsets.UTF_8);
        } else  {
            valueBytes = new byte[0];
        }

        int totalSize = HEADER_SIZE + keyBytes.length + valueBytes.length;
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);

        buffer.position(4);

        OpType opType = OpType.from(isDelete);
        buffer.put(opType.getCode());

        buffer.putInt(keyBytes.length);
        buffer.putInt(valueBytes.length);

        buffer.put(keyBytes);
        buffer.put(valueBytes);

        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 4,  totalSize-4);
        long checksum = crc.getValue();
        buffer.putInt(0, (int)checksum);

        buffer.flip();
        return buffer;
    }
}
