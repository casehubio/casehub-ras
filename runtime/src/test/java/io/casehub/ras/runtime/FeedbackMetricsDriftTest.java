package io.casehub.ras.runtime;

import io.casehub.ras.api.DriftDirection;
import io.casehub.ras.api.GanglionOutcomeStatistics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FeedbackMetricsDriftTest {

    @Test
    void setDriftGauges_registersAllFiveDirections() {
        var registry = new SimpleMeterRegistry();
        var metrics = new FeedbackMetrics(registry);
        metrics.setDriftGauges(DriftDirection.OVER_SENSITIVE, "sit1", "t1");

        Gauge active = registry.find("ras.feedback.drift")
                .tag("direction", "OVER_SENSITIVE")
                .tag("situation_id", "sit1").gauge();
        assertNotNull(active);
        assertEquals(1.0, active.value());

        Gauge inactive = registry.find("ras.feedback.drift")
                .tag("direction", "STABLE")
                .tag("situation_id", "sit1").gauge();
        assertNotNull(inactive);
        assertEquals(0.0, inactive.value());
    }

    @Test
    void setDriftGauges_directionChange_updatesCorrectly() {
        var registry = new SimpleMeterRegistry();
        var metrics = new FeedbackMetrics(registry);
        metrics.setDriftGauges(DriftDirection.OVER_SENSITIVE, "sit1", "t1");
        metrics.setDriftGauges(DriftDirection.STABLE, "sit1", "t1");

        Gauge overSensitive = registry.find("ras.feedback.drift")
                .tag("direction", "OVER_SENSITIVE")
                .tag("situation_id", "sit1").gauge();
        assertEquals(0.0, overSensitive.value());

        Gauge stable = registry.find("ras.feedback.drift")
                .tag("direction", "STABLE")
                .tag("situation_id", "sit1").gauge();
        assertEquals(1.0, stable.value());
    }

    @Test
    void setDriftGauges_multipleSituations_independent() {
        var registry = new SimpleMeterRegistry();
        var metrics = new FeedbackMetrics(registry);
        metrics.setDriftGauges(DriftDirection.OVER_SENSITIVE, "sit1", "t1");
        metrics.setDriftGauges(DriftDirection.UNDER_SENSITIVE, "sit2", "t1");

        Gauge sit1 = registry.find("ras.feedback.drift")
                .tag("direction", "OVER_SENSITIVE")
                .tag("situation_id", "sit1").gauge();
        assertEquals(1.0, sit1.value());

        Gauge sit2 = registry.find("ras.feedback.drift")
                .tag("direction", "UNDER_SENSITIVE")
                .tag("situation_id", "sit2").gauge();
        assertEquals(1.0, sit2.value());
    }

    @Test
    void recordGanglionStatistics_publishesRecallGauge() {
        var registry = new SimpleMeterRegistry();
        var metrics = new FeedbackMetrics(registry);
        var stats = new GanglionOutcomeStatistics("g1", 10, 2, 5, 3, 3);

        metrics.recordGanglionStatistics("g1", "sit1", "t1", stats);

        Gauge recall = registry.find("ras.feedback.ganglion.recall")
                .tag("ganglion_id", "g1").gauge();
        assertNotNull(recall);
        assertEquals(5.0 / 8.0, recall.value(), 0.0001);
    }

    @Test
    void recordGanglionStatistics_suppressesRecallGaugeWhenNaN() {
        var registry = new SimpleMeterRegistry();
        var metrics = new FeedbackMetrics(registry);
        var stats = new GanglionOutcomeStatistics("g1", 10, 2, 5, 3, 0);

        metrics.recordGanglionStatistics("g1", "sit1", "t1", stats);

        Gauge recall = registry.find("ras.feedback.ganglion.recall")
                .tag("ganglion_id", "g1").gauge();
        assertNull(recall);
    }
}
