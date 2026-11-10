package com.ibm.internship.lsm.sstable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BloomFilterTest {

    @Test
    @DisplayName("Should never report false negative for any inserted key")
    void shouldNeverReportFalseNegative() {
        BloomFilter filter = new BloomFilter(1000, 0.01);

        for (int i = 0; i < 1000; i++) {
            filter.add("user_" + i);
        }

        // All inserted keys MUST return true (0% false negatives)
        for (int i = 0; i < 1000; i++) {
            assertTrue(filter.mightContain("user_" + i), "Failed to find inserted key: user_" + i);
        }
    }

    @Test
    @DisplayName("Should return false for absent keys and maintain low false positive rate")
    void shouldMaintainLowFalsePositiveRate() {
        int insertedCount = 2000;
        double targetFpRate = 0.01; // 1%
        BloomFilter filter = new BloomFilter(insertedCount, targetFpRate);

        for (int i = 0; i < insertedCount; i++) {
            filter.add("present_key_" + i);
        }

        int falsePositives = 0;
        int testCount = 5000;

        for (int i = 0; i < testCount; i++) {
            if (filter.mightContain("absent_key_" + i)) {
                falsePositives++;
            }
        }

        double actualFpRate = (double) falsePositives / testCount;
        // False positive rate should be close to 1% (allow small margin up to 3%)
        assertTrue(actualFpRate < 0.03,
                String.format("False positive rate too high: %.4f (expected around %.2f)", actualFpRate, targetFpRate));
    }

    @Test
    @DisplayName("Should correctly serialize to bytes and deserialize back without losing data")
    void shouldSerializeAndDeserializeCorrectly() {
        BloomFilter original = new BloomFilter(500, 0.01);

        for (int i = 0; i < 500; i++) {
            original.add("item_" + i);
        }

        byte[] serialized = original.toByteArray();
        assertNotNull(serialized);
        assertTrue(serialized.length > 8, "Serialized bytes should contain header (8 bytes) + bitset data");

        BloomFilter restored = BloomFilter.fromByteArray(serialized);

        // Restored filter must retain all original keys
        for (int i = 0; i < 500; i++) {
            assertTrue(restored.mightContain("item_" + i), "Restored filter lost key: item_" + i);
        }

        // And absent keys should still be absent
        assertFalse(restored.mightContain("non_existent_key_xyz"));
    }

    @Test
    @DisplayName("Empty filter should return false for any queried key")
    void shouldReturnFalseForEmptyFilter() {
        BloomFilter filter = new BloomFilter(100, 0.01);

        assertFalse(filter.mightContain("any_key"));
        assertFalse(filter.mightContain("another_key"));
    }

    @Test
    @DisplayName("Should handle edge cases like null and zero expected keys gracefully")
    void shouldHandleEdgeCasesGracefully() {
        // Zero expected keys should not throw or divide by zero
        assertDoesNotThrow(() -> new BloomFilter(0, 0.01));

        BloomFilter filter = new BloomFilter(10, 0.01);

        // Null query should safely return false
        assertFalse(filter.mightContain(null));

        // Null add should throw NullPointerException
        assertThrows(NullPointerException.class, () -> filter.add(null));
    }
}
