package com.ibm.internship.lsm.sstable;

import com.ibm.internship.lsm.utils.MurmurHash;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;

public class BloomFilter {
    private final int numBits, hashCount;
    private final BitSet bits;

    public BloomFilter(int expectedNumOfKeys, double falsePositiveProbability) {
        int n = Math.max(1, expectedNumOfKeys);
        numBits = Math.max(64, (int) (-(n * Math.log(falsePositiveProbability)) / (Math.pow(Math.log(2), 2))));
        hashCount = Math.max(1, (int) (((double) numBits / n * Math.log(2))));
        bits = new BitSet(numBits);
    }

    private BloomFilter(BitSet bits, int numBits, int hashCount) {
        this.bits = bits;
        this.numBits = numBits;
        this.hashCount = hashCount;
    }

    public void add(String key) {
        if (key == null) {
            throw new NullPointerException("key is null");
        }

        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int hash1 = MurmurHash.hash32(keyBytes, keyBytes.length, 0);
        int hash2 = MurmurHash.hash32(keyBytes, keyBytes.length, 1);

        for (int i = 0; i < hashCount; i++) {
            int index = ((hash1 + i * hash2) & 0x7FFFFFFF) % numBits;
            bits.set(index);
        }
    }

    public boolean mightContain(String key) {
        if (key == null) {
            return false;
        }

        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int hash1 = MurmurHash.hash32(keyBytes, keyBytes.length, 0);
        int hash2 = MurmurHash.hash32(keyBytes, keyBytes.length, 1);

        for (int i = 0; i < hashCount; i++) {
            int index = ((hash1 + i * hash2) & 0x7FFFFFFF) % numBits;
            if (!bits.get(index)) {
                return false;
            }
        }

        return true;
    }

    public byte[] toByteArray() {
        byte[] bitBytes = bits.toByteArray();
        ByteBuffer buffer = ByteBuffer.allocate(8 + bitBytes.length);
        buffer.putInt(hashCount);
        buffer.putInt(numBits);
        buffer.put(bitBytes);
        return buffer.array();
    }

    public static BloomFilter fromByteArray(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int hashCount = buffer.getInt();
        int numBits = buffer.getInt();
        byte[] bitBytes = new byte[buffer.remaining()];
        buffer.get(bitBytes);
        BitSet bits = BitSet.valueOf(bitBytes);

        return new BloomFilter(bits, numBits, hashCount);
    }
}
