package io.casehub.ras.runtime;

import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.TimestampedDetection;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.TriggerDecision;
import io.casehub.ras.api.TriggerMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRasTriggerPolicyTest {

    private final DefaultRasTriggerPolicy policy = new DefaultRasTriggerPolicy();

    private static final Instant T1 = Instant.parse("2026-06-25T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-25T10:01:00Z");
    private static final Instant T3 = Instant.parse("2026-06-25T10:02:00Z");
    private static final Instant T4 = Instant.parse("2026-06-25T10:03:00Z");
    private static final Instant T5 = Instant.parse("2026-06-25T10:04:00Z");
    private static final CaseTriggerConfig TRIGGER = new CaseTriggerConfig("ns", "c", "1", Map.of());

    private SituationDefinition def(ChainMode mode) {
        return def(mode, null);
    }

    private SituationDefinition def(ChainMode mode, TriggerMode triggerMode) {
        return new SituationDefinition("sit", Set.of("e"), Duration.ofMinutes(10), null, mode,
                new TriggerAction.CreateCase(TRIGGER), triggerMode);
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

    private SituationContext contextWithDetection(String ganglionId, double confidence) {
        return ctx(td(ganglionId, DetectionSignal.DETECTED, confidence, T1));
    }

    private SituationContext contextWithDetectionAndTrigger(String ganglionId, double confidence,
                                                             Instant lastTriggered, Instant lastSignal) {
        var ctx = SituationContext.initial("sit", "key", "tenant", T1);
        ctx = ctx.withDetection(new DetectionResult(ganglionId, confidence, DetectionSignal.DETECTED, Map.of()), lastSignal);
        return new SituationContext(
                ctx.situationId(),
                ctx.correlationKey(),
                ctx.tenancyId(),
                ctx.firstSignal(),
                ctx.lastSignal(),
                ctx.detections(),
                ctx.storeVersion(),
                lastTriggered,
                1
        );
    }

    // --- AND ---

    @Test
    void andSatisfiedWhenAllGangliaFired() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.WEAK, 0.5, T2)),
                def(new ChainMode.And(Set.of("g1", "g2")))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void andNotSatisfiedWhenGanglionMissing() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1)),
                def(new ChainMode.And(Set.of("g1", "g2")))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void andIgnoresNoiseDetections() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.NOISE, 0.0, T2)),
                def(new ChainMode.And(Set.of("g1", "g2")))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void andIgnoresAntiDetections() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.ANTI, 0.7, T2)),
                def(new ChainMode.And(Set.of("g1", "g2")))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- OR ---

    @Test
    void orSatisfiedWhenAnyGanglionFired() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1)),
                def(new ChainMode.Or(Set.of("g1", "g2")))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void orNotSatisfiedWithOnlyNoise() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.NOISE, 0.0, T1)),
                def(new ChainMode.Or(Set.of("g1", "g2")))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- THRESHOLD ---

    @Test
    void thresholdSatisfiedWhenConfidenceSumMet() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.5, T1),
                    td("g2", DetectionSignal.WEAK, 0.4, T2)),
                def(new ChainMode.Threshold(Set.of("g1", "g2"), 0.8))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void thresholdNotSatisfiedBelowMinConfidence() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.3, T1)),
                def(new ChainMode.Threshold(Set.of("g1", "g2"), 0.8))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void thresholdExcludesNoiseFromSum() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.5, T1),
                    td("g2", DetectionSignal.NOISE, 0.5, T2)),
                def(new ChainMode.Threshold(Set.of("g1", "g2"), 0.8))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- SEQUENCE ---

    @Test
    void sequenceSatisfiedInOrder() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.DETECTED, 0.8, T2)),
                def(new ChainMode.Sequence(List.of("g1", "g2")))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void sequenceFailsOutOfOrder() {
        var result = policy.evaluate(
                ctx(td("g2", DetectionSignal.DETECTED, 0.8, T1),
                    td("g1", DetectionSignal.DETECTED, 0.9, T2)),
                def(new ChainMode.Sequence(List.of("g1", "g2")))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void sequenceSortsByEventTimeNotArrivalOrder() {
        // Arrival order: g2@T2, g1@T1. Event-time order: g1@T1, g2@T2 → sequence satisfied.
        var result = policy.evaluate(
                ctx(td("g2", DetectionSignal.DETECTED, 0.8, T2),
                    td("g1", DetectionSignal.DETECTED, 0.9, T1)),
                def(new ChainMode.Sequence(List.of("g1", "g2")))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void sequenceIncomplete() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1)),
                def(new ChainMode.Sequence(List.of("g1", "g2")))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- STREAK ---

    @Test
    void streakSatisfiedWithConsecutiveDetections() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.DETECTED, 0.8, T2),
                    td("g1", DetectionSignal.WEAK, 0.5, T3)),
                def(new ChainMode.Streak("g1", 3))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void streakResetByAnti() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.ANTI, 0.7, T2),
                    td("g1", DetectionSignal.DETECTED, 0.8, T3)),
                def(new ChainMode.Streak("g1", 2))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void streakIgnoresNoise() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.NOISE, 0.0, T2),
                    td("g1", DetectionSignal.DETECTED, 0.8, T3)),
                def(new ChainMode.Streak("g1", 2))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void streakIgnoresOtherGanglia() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.ANTI, 0.7, T2),
                    td("g1", DetectionSignal.DETECTED, 0.8, T3)),
                def(new ChainMode.Streak("g1", 2))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void streakNotSatisfiedBelowRequired() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1)),
                def(new ChainMode.Streak("g1", 2))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void streakSortsByEventTimeNotArrivalOrder() {
        // Arrival order: ANTI@T2, DETECTED@T1, DETECTED@T3
        // Event-time order: DETECTED@T1, ANTI@T2, DETECTED@T3 → streak=1, not 2
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.ANTI, 0.7, T2),
                    td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.DETECTED, 0.8, T3)),
                def(new ChainMode.Streak("g1", 2))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- RATE ---

    @Test
    void rateSatisfiedWhenRatioMet() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.DETECTED, 0.8, T2),
                    td("g1", DetectionSignal.ANTI, 0.5, T3)),
                def(new ChainMode.Rate(Set.of("g1"), 0.6, 3))
        );
        // 2 qualifying / 3 total = 0.67 >= 0.6
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void rateNotSatisfiedBelowMinRate() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.ANTI, 0.5, T2),
                    td("g1", DetectionSignal.ANTI, 0.5, T3)),
                def(new ChainMode.Rate(Set.of("g1"), 0.6, 3))
        );
        // 1 qualifying / 3 total = 0.33 < 0.6
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void rateNotSatisfiedWhenWindowNotFull() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.DETECTED, 0.8, T2)),
                def(new ChainMode.Rate(Set.of("g1"), 0.5, 3))
        );
        // Only 2 scoreable signals, window needs 3
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void rateExcludesNoiseFromWindow() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.NOISE, 0.0, T2),
                    td("g1", DetectionSignal.DETECTED, 0.8, T3)),
                def(new ChainMode.Rate(Set.of("g1"), 0.5, 3))
        );
        // Only 2 scoreable (NOISE excluded), window needs 3
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void rateUsesLastWindowSizeSignals() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.ANTI, 0.5, T2),
                    td("g1", DetectionSignal.DETECTED, 0.8, T3),
                    td("g1", DetectionSignal.DETECTED, 0.7, T4)),
                def(new ChainMode.Rate(Set.of("g1"), 0.6, 3))
        );
        // Last 3 scoreable: ANTI@T2, DETECTED@T3, DETECTED@T4 → 2/3 = 0.67 >= 0.6
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void rateAcrossMultipleGanglia() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.ANTI, 0.5, T2),
                    td("g1", DetectionSignal.DETECTED, 0.8, T3)),
                def(new ChainMode.Rate(Set.of("g1", "g2"), 0.6, 3))
        );
        // 2 qualifying / 3 total = 0.67 >= 0.6
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void rateIgnoresNonParticipatingGanglia() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g3", DetectionSignal.ANTI, 0.5, T2),
                    td("g1", DetectionSignal.DETECTED, 0.8, T3)),
                def(new ChainMode.Rate(Set.of("g1"), 0.5, 2))
        );
        // g3 not in ganglia → ignored; 2 qualifying / 2 total = 1.0 >= 0.5
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void rateSortsByEventTimeNotArrivalOrder() {
        // Arrival order: DETECTED@T3, ANTI@T1, DETECTED@T2
        // Event-time order: ANTI@T1, DETECTED@T2, DETECTED@T3
        // Last 2: DETECTED@T2, DETECTED@T3 → 2/2 = 1.0 >= 0.5
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.8, T3),
                    td("g1", DetectionSignal.ANTI, 0.5, T1),
                    td("g1", DetectionSignal.DETECTED, 0.9, T2)),
                def(new ChainMode.Rate(Set.of("g1"), 0.5, 2))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    // --- COUNT ---

    @Test
    void countSatisfiedWhenEnoughDetections() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.WEAK, 0.5, T2),
                    td("g1", DetectionSignal.DETECTED, 0.8, T3)),
                def(new ChainMode.Count("g1", 3))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void countNotSatisfiedBelowRequired() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.WEAK, 0.5, T2)),
                def(new ChainMode.Count("g1", 3))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void countExcludesNoiseDetections() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g1", DetectionSignal.NOISE, 0.0, T2),
                    td("g1", DetectionSignal.DETECTED, 0.8, T3)),
                def(new ChainMode.Count("g1", 3))
        );
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- ANTI SIGNAL HANDLING ---

    @Test
    void thresholdSubtractsAntiConfidence() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.ANTI, 0.5, T2)),
                def(new ChainMode.Threshold(Set.of("g1", "g2"), 0.8))
        );
        // 0.9 - 0.5 = 0.4, below 0.8 threshold
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void thresholdAntiCanPullBelowThreshold() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.5, T1),
                    td("g2", DetectionSignal.DETECTED, 0.4, T2),
                    td("g1", DetectionSignal.ANTI, 0.3, T3)),
                def(new ChainMode.Threshold(Set.of("g1", "g2"), 0.8))
        );
        // 0.5 + 0.4 - 0.3 = 0.6, below 0.8 threshold
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void thresholdStillSatisfiedDespiteAntiWhenSumSufficient() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g2", DetectionSignal.DETECTED, 0.5, T2),
                    td("g1", DetectionSignal.ANTI, 0.3, T3)),
                def(new ChainMode.Threshold(Set.of("g1", "g2"), 0.8))
        );
        // 0.9 + 0.5 - 0.3 = 1.1, above 0.8
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void thresholdIgnoresAntiFromNonParticipatingGanglia() {
        var result = policy.evaluate(
                ctx(td("g1", DetectionSignal.DETECTED, 0.9, T1),
                    td("g3", DetectionSignal.ANTI, 0.5, T2)),
                def(new ChainMode.Threshold(Set.of("g1", "g2"), 0.8))
        );
        // g3 not in threshold ganglia → ignored; 0.9 >= 0.8
        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
    }

    // --- EMPTY CONTEXT ---

    @Test
    void emptyContextNeverSatisfied() {
        var ctx = SituationContext.initial("sit", "key", "tenant", T1);
        var result = policy.evaluate(ctx, def(new ChainMode.Or(Set.of("g1"))))
                ;
        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    // --- TRIGGER MODE ---

    @Test
    void fireOnceReturnsCreateCase() {
        var def = def(new ChainMode.Or(Set.of("g1")), new TriggerMode.FireOnce());
        var ctx = contextWithDetection("g1", 0.9);
        assertThat(policy.evaluate(ctx, def).decision())
                .isEqualTo(TriggerDecision.TRIGGER);
    }

    @Test
    void repeatingReturnsCreateCaseAndContinueOnFirstTrigger() {
        var def = def(new ChainMode.Or(Set.of("g1")),
                new TriggerMode.Repeating(Duration.ofMinutes(5)));
        var ctx = contextWithDetection("g1", 0.9);
        assertThat(policy.evaluate(ctx, def).decision())
                .isEqualTo(TriggerDecision.TRIGGER_AND_CONTINUE);
    }

    @Test
    void repeatingReturnsContinueAccumulatingDuringCooldown() {
        var def = def(new ChainMode.Or(Set.of("g1")),
                new TriggerMode.Repeating(Duration.ofMinutes(5)));
        var ctx = contextWithDetectionAndTrigger("g1", 0.9,
                T1, T1.plus(Duration.ofMinutes(1)));
        assertThat(policy.evaluate(ctx, def).decision())
                .isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
    }

    @Test
    void repeatingReturnsCreateCaseAndContinueAfterCooldown() {
        var def = def(new ChainMode.Or(Set.of("g1")),
                new TriggerMode.Repeating(Duration.ofMinutes(5)));
        var ctx = contextWithDetectionAndTrigger("g1", 0.9,
                T1, T1.plus(Duration.ofMinutes(10)));
        assertThat(policy.evaluate(ctx, def).decision())
                .isEqualTo(TriggerDecision.TRIGGER_AND_CONTINUE);
    }
}
