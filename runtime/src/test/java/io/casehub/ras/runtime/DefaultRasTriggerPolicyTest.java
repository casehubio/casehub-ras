package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class DefaultRasTriggerPolicyTest {

    private final DefaultRasTriggerPolicy policy = new DefaultRasTriggerPolicy();

    private static final Instant T1 = Instant.parse("2026-06-25T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-25T10:01:00Z");
    private static final Instant T3 = Instant.parse("2026-06-25T10:02:00Z");
    private static final CaseTriggerConfig TRIGGER = new CaseTriggerConfig("ns", "c", "1", Map.of());

    private SituationDefinition def(ChainMode mode) {
        return new SituationDefinition("sit", Set.of("e"), Duration.ofMinutes(10), mode, TRIGGER);
    }

    private SituationContext ctx(TimestampedDetection... detections) {
        var ctx = SituationContext.initial("sit", "key", "tenant", T1);
        for (var td : detections) {
            ctx = ctx.withDetection(td.result(), td.eventTime());
        }
        return ctx;
    }

    private TimestampedDetection td(String ganglionId, DetectionSignal signal, double confidence, Instant time) {
        return new TimestampedDetection(
                new DetectionResult(ganglionId, confidence, signal, Map.of()), time);
    }

    // --- AND ---

    @Test
    void andSatisfiedWhenAllGangliaFired() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.WEAK, 0.5, T2)),
                def(new ChainMode.And(Set.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
    }

    @Test
    void andNotSatisfiedWhenGanglionMissing() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1)),
                def(new ChainMode.And(Set.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void andIgnoresNoiseDetections() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.NOISE, 0.0, T2)),
                def(new ChainMode.And(Set.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void andIgnoresAntiDetections() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.ANTI, 0.7, T2)),
                def(new ChainMode.And(Set.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- OR ---

    @Test
    void orSatisfiedWhenAnyGanglionFired() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1)),
                def(new ChainMode.Or(Set.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
    }

    @Test
    void orNotSatisfiedWithOnlyNoise() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.NOISE, 0.0, T1)),
                def(new ChainMode.Or(Set.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- THRESHOLD ---

    @Test
    void thresholdSatisfiedWhenConfidenceSumMet() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.5, T1),
                    td("g2", DetectionSignal.WEAK, 0.4, T2)),
                def(new ChainMode.Threshold(Set.of("g1", "g2"), 0.8))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
    }

    @Test
    void thresholdNotSatisfiedBelowMinConfidence() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.3, T1)),
                def(new ChainMode.Threshold(Set.of("g1", "g2"), 0.8))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void thresholdExcludesNoiseFromSum() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.5, T1),
                    td("g2", DetectionSignal.NOISE, 0.5, T2)),
                def(new ChainMode.Threshold(Set.of("g1", "g2"), 0.8))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- SEQUENCE ---

    @Test
    void sequenceSatisfiedInOrder() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.DETECTED, 0.8, T2)),
                def(new ChainMode.Sequence(List.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
    }

    @Test
    void sequenceFailsOutOfOrder() {
        var result = policy.evaluate(
                ctx(td("g2", DetectionSignal.DETECTED, 0.8, T1),
                    td("g1", DetectionSignal.DETECTED, 0.9, T2)),
                def(new ChainMode.Sequence(List.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void sequenceSortsByEventTimeNotArrivalOrder() {
        // Arrival order: g2@T2, g1@T1. Event-time order: g1@T1, g2@T2 → sequence satisfied.
        var result = policy.evaluate(
                ctx(td("g2", DetectionSignal.DETECTED, 0.8, T2),
                    td("g1", DetectionSignal.DETECTED, 0.9, T1)),
                def(new ChainMode.Sequence(List.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
    }

    @Test
    void sequenceIncomplete() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1)),
                def(new ChainMode.Sequence(List.of("g1", "g2")))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- COUNT ---

    @Test
    void countSatisfiedWhenEnoughDetections() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.WEAK, 0.5, T2),
                    td("g1", DetectionSignal.DETECTED, 0.8, T3)),
                def(new ChainMode.Count("g1", 3))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CREATE_CASE);
    }

    @Test
    void countNotSatisfiedBelowRequired() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.WEAK, 0.5, T2)),
                def(new ChainMode.Count("g1", 3))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void countExcludesNoiseDetections() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.NOISE, 0.0, T2),
                    td("g1", DetectionSignal.DETECTED, 0.8, T3)),
                def(new ChainMode.Count("g1", 3))
        ).await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- EMPTY CONTEXT ---

    @Test
    void emptyContextNeverSatisfied() {
        var ctx = SituationContext.initial("sit", "key", "tenant", T1);
        var result = policy.evaluate(ctx, def(new ChainMode.Or(Set.of("g1"))))
                .await().indefinitely();
        assertThat(result).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }
}
