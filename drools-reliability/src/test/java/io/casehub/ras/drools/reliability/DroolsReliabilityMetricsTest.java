package io.casehub.ras.drools.reliability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DroolsReliabilityMetricsTest {

    private SimpleMeterRegistry registry;
    private DroolsReliabilityMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new DroolsReliabilityMetrics();
        metrics.setMeterRegistry(registry);
        metrics.init();
    }

    @Test
    void sessionCreated_increments_counter() {
        metrics.sessionCreated("g1");
        assertThat(registry.counter("ras.drools.session.created", "ganglion_id", "g1").count())
                .isEqualTo(1.0);
        metrics.sessionCreated("g1");
        assertThat(registry.counter("ras.drools.session.created", "ganglion_id", "g1").count())
                .isEqualTo(2.0);
    }

    @Test
    void sessionEvicted_increments_counter() {
        metrics.sessionEvicted("g1");
        assertThat(registry.counter("ras.drools.session.evicted", "ganglion_id", "g1").count())
                .isEqualTo(1.0);
    }

    @Test
    void sessionRecovered_increments_counter() {
        metrics.sessionRecovered("g1");
        assertThat(registry.counter("ras.drools.session.recovered", "ganglion_id", "g1").count())
                .isEqualTo(1.0);
    }

    @Test
    void sessionRecoveryFailed_increments_counter() {
        metrics.sessionRecoveryFailed("g1");
        assertThat(registry.counter("ras.drools.session.recovery_failed", "ganglion_id", "g1").count())
                .isEqualTo(1.0);
    }

    @Test
    void sessionRemoved_increments_counter() {
        metrics.sessionRemoved("g1");
        assertThat(registry.counter("ras.drools.session.removed", "ganglion_id", "g1").count())
                .isEqualTo(1.0);
    }

    @Test
    void storeWriteFailed_increments_counter() {
        metrics.storeWriteFailed("g1");
        assertThat(registry.counter("ras.drools.store.write_failed", "ganglion_id", "g1").count())
                .isEqualTo(1.0);
    }

    @Test
    void storeCorruptionRecovered_increments_counter() {
        metrics.storeCorruptionRecovered();
        assertThat(registry.counter("ras.drools.store.corruption_recovered").count())
                .isEqualTo(1.0);
    }


    @Test
    void timer_roundTrip() {
        Object sample = metrics.startComputeTimer();
        assertThat(sample).isNotNull();
        metrics.stopComputeTimer(sample, "g1", "hit");
        assertThat(registry.timer("ras.drools.session.compute_time",
                "ganglion_id", "g1", "outcome", "hit").count()).isEqualTo(1);
    }

    @Test
    void timer_records_different_outcomes() {
        Object s1 = metrics.startComputeTimer();
        metrics.stopComputeTimer(s1, "g1", "hit");
        Object s2 = metrics.startComputeTimer();
        metrics.stopComputeTimer(s2, "g1", "created");
        assertThat(registry.timer("ras.drools.session.compute_time",
                "ganglion_id", "g1", "outcome", "hit").count()).isEqualTo(1);
        assertThat(registry.timer("ras.drools.session.compute_time",
                "ganglion_id", "g1", "outcome", "created").count()).isEqualTo(1);
    }

    @Test
    void activeSessionsGauge_reflects_supplier() {
        var count = new AtomicInteger(0);
        metrics.registerActiveSessionsGauge(count::get);
        assertThat(registry.get("ras.drools.session.active").gauge().value()).isEqualTo(0.0);
        count.set(3);
        assertThat(registry.get("ras.drools.session.active").gauge().value()).isEqualTo(3.0);
    }

    @Test
    void counters_separate_by_ganglionId() {
        metrics.sessionCreated("g1");
        metrics.sessionCreated("g2");
        metrics.sessionCreated("g2");
        assertThat(registry.counter("ras.drools.session.created", "ganglion_id", "g1").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("ras.drools.session.created", "ganglion_id", "g2").count())
                .isEqualTo(2.0);
    }

    @Test
    void works_without_metrics() {
        var noMetrics = new DroolsReliabilityMetrics();
        noMetrics.init();
        // All operations should be safe no-ops
        noMetrics.sessionCreated("g1");
        noMetrics.sessionEvicted("g1");
        noMetrics.sessionRecovered("g1");
        noMetrics.sessionRecoveryFailed("g1");
        noMetrics.sessionRemoved("g1");
        noMetrics.storeWriteFailed("g1");
        noMetrics.storeCorruptionRecovered();
        Object sample = noMetrics.startComputeTimer();
        assertThat(sample).isNull();
        noMetrics.stopComputeTimer(sample, "g1", "hit");
        noMetrics.registerActiveSessionsGauge(() -> 0);
    }
}
