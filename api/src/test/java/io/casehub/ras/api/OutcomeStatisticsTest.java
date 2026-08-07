package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class OutcomeStatisticsTest {

    @Test
    void precisionWithMixedOutcomes() {
        var stats = new OutcomeStatistics("s1", "t1", 10, 3, 7, 0, Instant.now());
        assertEquals(0.7, stats.precision(), 0.001);
    }

    @Test
    void precisionNaNWhenNoDecisiveOutcomes() {
        var stats = new OutcomeStatistics("s1", "t1", 5, 0, 0, 5, Instant.now());
        assertTrue(Double.isNaN(stats.precision()));
    }

    @Test
    void noiseRateComputation() {
        var stats = new OutcomeStatistics("s1", "t1", 10, 6, 4, 0, Instant.now());
        assertEquals(0.6, stats.noiseRate(), 0.001);
    }

    @Test
    void noiseRateNaNWhenEmpty() {
        var stats = new OutcomeStatistics("s1", "t1", 0, 0, 0, 0, Instant.now());
        assertTrue(Double.isNaN(stats.noiseRate()));
    }

    @Test
    void precisionAllNoise() {
        var stats = new OutcomeStatistics("s1", "t1", 5, 5, 0, 0, Instant.now());
        assertEquals(0.0, stats.precision(), 0.001);
    }

    @Test
    void precisionAllConfirmed() {
        var stats = new OutcomeStatistics("s1", "t1", 5, 0, 5, 0, Instant.now());
        assertEquals(1.0, stats.precision(), 0.001);
    }
}
