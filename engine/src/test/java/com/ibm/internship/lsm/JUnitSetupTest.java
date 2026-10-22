package com.ibm.internship.lsm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JUnitSetupTest {

    @Test
    void shouldVerifyJUnit5Setup() {
        int expected = 5;
        int actual = 2 + 3;

        assertEquals(expected, actual);
        assertTrue(true);
    }
}
