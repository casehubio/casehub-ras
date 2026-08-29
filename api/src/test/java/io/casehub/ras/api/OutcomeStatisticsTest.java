package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void recallComputedFromConfirmedAndMissed() {
        var stats = new OutcomeStatistics("sit-1", "tenant-a", 10, 2, 7, 1,
                                          Instant.parse("2026-01-01T00:00:00Z"), 3);
        assertThat(stats.recall()).isCloseTo(0.7, within(0.001));
    }

    @Test
    void recallNaNWhenNoConfirmedOrMissed() {
        var stats = new OutcomeStatistics("sit-1", "tenant-a", 5, 0, 0, 5,
                                          Instant.parse("2026-01-01T00:00:00Z"), 0);
        assertThat(stats.recall()).isNaN();
    }

    @Test
    void recallOneWhenNoMissed() {
        var stats = new OutcomeStatistics("sit-1", "tenant-a", 5, 0, 5, 0,
                                          Instant.parse("2026-01-01T00:00:00Z"), 0);
        assertThat(stats.recall()).isEqualTo(1.0);
    }

    @Test
    void backwardsCompatConstructorDefaultsMissedToZero() {
        var stats = new OutcomeStatistics("sit-1", "tenant-a", 5, 1, 3, 1,
                                          Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(stats.missedCount()).isEqualTo(0);
        assertThat(stats.recall()).isEqualTo(1.0);
    }

}
