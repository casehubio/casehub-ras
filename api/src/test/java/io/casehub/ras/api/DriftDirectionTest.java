package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DriftDirectionTest {

    @Test
    void enumHasFiveValues() {
        assertEquals(5, DriftDirection.values().length);
        assertNotNull(DriftDirection.OVER_SENSITIVE);
        assertNotNull(DriftDirection.UNDER_SENSITIVE);
        assertNotNull(DriftDirection.BOTH_DRIFTING);
        assertNotNull(DriftDirection.STABLE);
        assertNotNull(DriftDirection.INSUFFICIENT_DATA);
    }

    @Test
    void valueOfRoundTrips() {
        for (DriftDirection d : DriftDirection.values()) {
            assertEquals(d, DriftDirection.valueOf(d.name()));
        }
    }
}
