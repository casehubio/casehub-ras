package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockGanglion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class RasMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private SituationDefinitionRegistry registry;
    private RasMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        var ganglion = new MockGanglion("g1", Set.of("e"),
                FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-1", Set.of("e"),
                Duration.ofMinutes(5), null,
                new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())),
                null);
        registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))),
                List.of(ganglion));
        metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(meterRegistry);
        metrics.init();
    }

    @Test
    void eventReceivedIncrementsCounterWithEventTypeTag() {
        metrics.eventReceived("temp.reading");
        metrics.eventReceived("temp.reading");
        metrics.eventReceived("pressure.alert");

        assertThat(meterRegistry.counter("ras.engine.events.received",
                "event_type", "temp.reading").count()).isEqualTo(2.0);
        assertThat(meterRegistry.counter("ras.engine.events.received",
                "event_type", "pressure.alert").count()).isEqualTo(1.0);
    }

    @Test
    void eventSkippedIncrementsCounterWithReasonTag() {
        metrics.eventSkipped("no_tenancy_id");
        assertThat(meterRegistry.counter("ras.engine.events.skipped",
                "reason", "no_tenancy_id").count()).isEqualTo(1.0);
    }

    @Test
    void eventRoutedIncrementsWithSituationAndTenancy() {
        metrics.eventRouted("sit-1", "tenant-a");
        assertThat(meterRegistry.counter("ras.engine.events.routed",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1.0);
    }

    @Test
    void evaluationFailedIncrementsWithSituationAndTenancy() {
        metrics.evaluationFailed("sit-1", "tenant-a");
        assertThat(meterRegistry.counter("ras.engine.evaluation.failed",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1.0);
    }

    @Test
    void decisionCounterUsesLowercaseDecisionTag() {
        metrics.decision("sit-1", "tenant-a", TriggerDecision.TRIGGER);
        metrics.decision("sit-1", "tenant-a", TriggerDecision.CONTINUE_ACCUMULATING);

        assertThat(meterRegistry.counter("ras.evaluator.decision",
                "situation_id", "sit-1", "tenancy_id", "tenant-a",
                "decision", "trigger").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("ras.evaluator.decision",
                "situation_id", "sit-1", "tenancy_id", "tenant-a",
                "decision", "continue_accumulating").count()).isEqualTo(1.0);
    }

    @Test
    void conflictRetryAndRetriesExhausted() {
        metrics.conflictRetry("sit-1", "tenant-a");
        metrics.conflictRetry("sit-1", "tenant-a");
        metrics.retriesExhausted("sit-1", "tenant-a");

        assertThat(meterRegistry.counter("ras.evaluator.conflict_retries",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(2.0);
        assertThat(meterRegistry.counter("ras.evaluator.retries_exhausted",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1.0);
    }

    @Test
    void contextExpired() {
        metrics.contextExpired("sit-1", "tenant-a");
        assertThat(meterRegistry.counter("ras.evaluator.context_expired",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1.0);
    }

    @Test
    void ganglionFailureCounters() {
        metrics.ganglionDetectFailed("g1", "sit-1");
        metrics.ganglionCompactFailed("g1", "sit-1");
        metrics.ganglionCloseFailed("g1", "sit-1");

        assertThat(meterRegistry.counter("ras.evaluator.ganglion.detect_failed",
                "ganglion_id", "g1", "situation_id", "sit-1").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("ras.evaluator.ganglion.compact_failed",
                "ganglion_id", "g1", "situation_id", "sit-1").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("ras.evaluator.ganglion.close_failed",
                "ganglion_id", "g1", "situation_id", "sit-1").count()).isEqualTo(1.0);
    }

    @Test
    void triggerClaimedAndRaceLost() {
        metrics.triggerClaimed("sit-1", "tenant-a");
        metrics.triggerRaceLost("sit-1", "tenant-a");

        assertThat(meterRegistry.counter("ras.evaluator.trigger.claimed",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("ras.evaluator.trigger.race_lost",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1.0);
    }

    @Test
    void triggerFireTimerRecordsDuration() {
        Object sample = metrics.startTriggerFireTimer();
        assertThat(sample).isNotNull();
        metrics.stopTriggerFireTimer(sample, "sit-1", "tenant-a", "create_case");

        assertThat(meterRegistry.timer("ras.evaluator.trigger.fire_time",
                "situation_id", "sit-1", "tenancy_id", "tenant-a",
                "trigger_action", "create_case").count()).isEqualTo(1);
    }

    @Test
    void triggerFiredAndFailedWithTriggerActionTag() {
        metrics.triggerFired("sit-1", "tenant-a", "create_case");
        metrics.triggerFailed("sit-1", "tenant-a", "notify_only");

        assertThat(meterRegistry.counter("ras.evaluator.trigger.fired",
                "situation_id", "sit-1", "tenancy_id", "tenant-a",
                "trigger_action", "create_case").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("ras.evaluator.trigger.failed",
                "situation_id", "sit-1", "tenancy_id", "tenant-a",
                "trigger_action", "notify_only").count()).isEqualTo(1.0);
    }

    @Test
    void processTimerRecordsDuration() {
        Object sample = metrics.startProcessTimer();
        assertThat(sample).isNotNull();
        metrics.stopProcessTimer(sample, "sit-1", "tenant-a");

        assertThat(meterRegistry.timer("ras.evaluator.process_time",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1);
    }

    @Test
    void eventBuffered() {
        metrics.eventBuffered("sit-1", "tenant-a");
        assertThat(meterRegistry.counter("ras.evaluator.buffer.events_buffered",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count())
                .isEqualTo(1.0);
    }

    @Test
    void triggeredCleanedIncrementsByCount() {
        metrics.triggeredCleaned(5);
        assertThat(meterRegistry.counter("ras.expiry.triggered_cleaned").count())
                .isEqualTo(5.0);
    }

    @Test
    void expiredCleanedIncrementsByCount() {
        metrics.expiredCleaned(3);
        assertThat(meterRegistry.counter("ras.expiry.expired_cleaned").count())
                .isEqualTo(3.0);
    }

    @Test
    void definitionsActiveGaugeReflectsRegistryCount() {
        assertThat(meterRegistry.get("ras.registry.definitions.active")
                .gauge().value()).isEqualTo(1.0);
    }

    @Test
    void activeBuffersGaugeRegisteredViaCallback() {
        AtomicInteger bufferCount = new AtomicInteger(3);
        metrics.registerActiveBuffersGauge(bufferCount::get);

        assertThat(meterRegistry.get("ras.evaluator.buffers.active")
                .gauge().value()).isEqualTo(3.0);

        bufferCount.set(7);
        assertThat(meterRegistry.get("ras.evaluator.buffers.active")
                .gauge().value()).isEqualTo(7.0);
    }

    @Test
    void allMethodsAreNoOpsWhenMeterRegistryIsNull() {
        var nullMetrics = new RasMetrics(registry);

        nullMetrics.eventReceived("type");
        nullMetrics.eventSkipped("reason");
        nullMetrics.eventRouted("sit", "tenant");
        nullMetrics.evaluationFailed("sit", "tenant");
        nullMetrics.decision("sit", "tenant", TriggerDecision.TRIGGER);
        nullMetrics.conflictRetry("sit", "tenant");
        nullMetrics.retriesExhausted("sit", "tenant");
        nullMetrics.contextExpired("sit", "tenant");
        nullMetrics.ganglionDetectFailed("g", "sit");
        nullMetrics.ganglionCompactFailed("g", "sit");
        nullMetrics.ganglionCloseFailed("g", "sit");
        nullMetrics.triggerClaimed("sit", "tenant");
        nullMetrics.triggerRaceLost("sit", "tenant");
        nullMetrics.triggerFired("sit", "tenant", "create_case");
        nullMetrics.triggerFailed("sit", "tenant", "notify_only");
        nullMetrics.eventBuffered("sit", "tenant");
        nullMetrics.triggeredCleaned(5);
        nullMetrics.expiredCleaned(3);
        nullMetrics.registerActiveBuffersGauge(() -> 0);

        Object sample = nullMetrics.startProcessTimer();
        assertThat(sample).isNull();
        nullMetrics.stopProcessTimer(null, "sit", "tenant");

        Object triggerSample = nullMetrics.startTriggerFireTimer();
        assertThat(triggerSample).isNull();
        nullMetrics.stopTriggerFireTimer(null, "sit", "tenant", "create_case");
    }
}
