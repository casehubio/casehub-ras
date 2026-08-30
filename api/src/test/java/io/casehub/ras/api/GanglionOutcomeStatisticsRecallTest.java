package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GanglionOutcomeStatisticsRecallTest {

    @Test
    void recallWithMissedData() {
        var stats = new GanglionOutcomeStatistics("g1", 10, 2, 5, 3, 3);
        assertEquals(5.0 / 8.0, stats.recall(), 0.0001);
    }

    @Test
    void recallNaN_whenMissedCountZero() {
        var stats = new GanglionOutcomeStatistics("g1", 10, 2, 5, 3, 0);
        assertTrue(Double.isNaN(stats.recall()));
    }

    @Test
    void recallNaN_whenBothZero() {
        var stats = new GanglionOutcomeStatistics("g1", 0, 0, 0, 0, 0);
        assertTrue(Double.isNaN(stats.recall()));
    }

    @Test
    void recallZero_allMissedNoConfirmed() {
        var stats = new GanglionOutcomeStatistics("g1", 0, 0, 0, 0, 5);
        assertEquals(0.0, stats.recall(), 0.0001);
    }

    @Test
    void fiveArgConstructorDefaultsMissedToZero() {
        var stats = new GanglionOutcomeStatistics("g1", 10, 2, 5, 3);
        assertEquals(0, stats.missedCount());
        assertTrue(Double.isNaN(stats.recall()));
    }

    @Test
    void precisionAndNoiseRateUnchanged() {
        var stats = new GanglionOutcomeStatistics("g1", 10, 3, 5, 2, 4);
        assertEquals(5.0 / 8.0, stats.precision(), 0.0001);
        assertEquals(3.0 / 10.0, stats.noiseRate(), 0.0001);
    }

    @Test
    void recallPerfect_whenNoMissesButHasMissedData() {
        var stats = new GanglionOutcomeStatistics("g1", 10, 2, 8, 0, 1);
        assertEquals(8.0 / 9.0, stats.recall(), 0.0001);
    }
}
