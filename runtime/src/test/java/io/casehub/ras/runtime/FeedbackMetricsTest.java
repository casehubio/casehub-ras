package io.casehub.ras.runtime;

import io.casehub.ras.api.GanglionOutcomeStatistics;
import io.casehub.ras.api.OutcomeStatistics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private FeedbackMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new FeedbackMetrics(meterRegistry);
    }

    @Test
    void recordGanglionStatisticsRegistersGauges() {
        var stats = new GanglionOutcomeStatistics("g1", 10, 3, 7, 0);
        metrics.recordGanglionStatistics("g1", "s1", "t1", stats);

        Gauge precision = meterRegistry.find("ras.feedback.ganglion.precision")
                .tag("ganglion_id", "g1").tag("situation_id", "s1")
                .tag("tenancy_id", "t1").gauge();
        assertNotNull(precision);
        assertEquals(0.7, precision.value(), 0.001);

        Gauge noiseRate = meterRegistry.find("ras.feedback.ganglion.noise_rate")
                .tag("ganglion_id", "g1").tag("situation_id", "s1")
                .tag("tenancy_id", "t1").gauge();
        assertNotNull(noiseRate);
        assertEquals(0.3, noiseRate.value(), 0.001);
    }

    @Test
    void gaugeHolderUpdatesOnSubsequentCalls() {
        var stats1 = new GanglionOutcomeStatistics("g1", 10, 5, 5, 0);
        metrics.recordGanglionStatistics("g1", "s1", "t1", stats1);

        Gauge precision = meterRegistry.find("ras.feedback.ganglion.precision")
                .tag("ganglion_id", "g1").gauge();
        assertEquals(0.5, precision.value(), 0.001);

        var stats2 = new GanglionOutcomeStatistics("g1", 20, 4, 16, 0);
        metrics.recordGanglionStatistics("g1", "s1", "t1", stats2);

        assertEquals(0.8, precision.value(), 0.001);
    }

    @Test
    void situationLevelGaugeHolderUpdates() {
        var stats1 = new OutcomeStatistics("s1", "t1", 10, 5, 5, 0, Instant.EPOCH);
        metrics.recordStatistics("s1", "t1", stats1);

        Gauge precision = meterRegistry.find("ras.feedback.precision")
                .tag("situation_id", "s1").gauge();
        assertNotNull(precision);
        assertEquals(0.5, precision.value(), 0.001);

        var stats2 = new OutcomeStatistics("s1", "t1", 20, 2, 18, 0, Instant.EPOCH);
        metrics.recordStatistics("s1", "t1", stats2);

        assertEquals(0.9, precision.value(), 0.001);
    }

    @Test
    void noOpWhenMeterRegistryNull() {
        var nullMetrics = new FeedbackMetrics((io.micrometer.core.instrument.MeterRegistry) null);
        var stats = new GanglionOutcomeStatistics("g1", 10, 3, 7, 0);
        assertDoesNotThrow(() -> nullMetrics.recordGanglionStatistics("g1", "s1", "t1", stats));
    }
}
