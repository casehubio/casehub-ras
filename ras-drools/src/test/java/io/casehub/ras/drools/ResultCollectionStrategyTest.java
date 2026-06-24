package io.casehub.ras.drools;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ResultCollectionStrategyTest {

    private DetectionResult result(DetectionSignal signal, double confidence,
            Map<String, Object> evidence) {
        return new DetectionResult("test-g", confidence, signal, evidence);
    }

    private DetectionResult result(DetectionSignal signal, double confidence) {
        return result(signal, confidence, Map.of());
    }

    // --- Empty list ---

    @Test
    void allStrategiesReturnNoiseForEmptyList() {
        for (var strategy : ResultCollectionStrategy.values()) {
            DetectionResult r = strategy.resolve(List.of(), "g1");
            assertThat(r.ganglionId()).isEqualTo("g1");
            assertThat(r.signal()).isEqualTo(DetectionSignal.NOISE);
            assertThat(r.confidence()).isEqualTo(0.0);
            assertThat(r.evidence()).isEmpty();
        }
    }

    // --- Single result ---

    @Test
    void selectStrategiesReturnSingleResultUnchanged() {
        var single = result(DetectionSignal.DETECTED, 0.8);
        assertThat(ResultCollectionStrategy.HIGHEST_CONFIDENCE
                .resolve(List.of(single), "g1")).isSameAs(single);
        assertThat(ResultCollectionStrategy.FIRST_MATCH
                .resolve(List.of(single), "g1")).isSameAs(single);
        assertThat(ResultCollectionStrategy.LAST_WINS
                .resolve(List.of(single), "g1")).isSameAs(single);
    }

    // --- HIGHEST_CONFIDENCE ---

    @Test
    void highestConfidencePicksMaxConfidence() {
        var low = result(DetectionSignal.DETECTED, 0.3);
        var high = result(DetectionSignal.WEAK, 0.9);
        var mid = result(DetectionSignal.DETECTED, 0.6);
        DetectionResult r = ResultCollectionStrategy.HIGHEST_CONFIDENCE
                .resolve(List.of(low, high, mid), "g1");
        assertThat(r).isSameAs(high);
    }

    @Test
    void highestConfidenceTiesPickFirstEncountered() {
        var first = result(DetectionSignal.DETECTED, 0.7);
        var second = result(DetectionSignal.WEAK, 0.7);
        DetectionResult r = ResultCollectionStrategy.HIGHEST_CONFIDENCE
                .resolve(List.of(first, second), "g1");
        assertThat(r).isSameAs(first);
    }

    // --- FIRST_MATCH ---

    @Test
    void firstMatchPicksFirstResult() {
        var first = result(DetectionSignal.WEAK, 0.3);
        var second = result(DetectionSignal.DETECTED, 0.9);
        DetectionResult r = ResultCollectionStrategy.FIRST_MATCH
                .resolve(List.of(first, second), "g1");
        assertThat(r).isSameAs(first);
    }

    // --- LAST_WINS ---

    @Test
    void lastWinsPicksLastResult() {
        var first = result(DetectionSignal.DETECTED, 0.9);
        var second = result(DetectionSignal.WEAK, 0.3);
        DetectionResult r = ResultCollectionStrategy.LAST_WINS
                .resolve(List.of(first, second), "g1");
        assertThat(r).isSameAs(second);
    }

    // --- ACCUMULATE ---

    @Test
    void accumulatePicksStrongestSignal() {
        var weak = result(DetectionSignal.WEAK, 0.3);
        var anti = result(DetectionSignal.ANTI, 0.5);
        DetectionResult r = ResultCollectionStrategy.ACCUMULATE
                .resolve(List.of(weak, anti), "g1");
        assertThat(r.signal()).isEqualTo(DetectionSignal.WEAK);
    }

    @Test
    void accumulatePicksMaxConfidenceRegardlessOfSignal() {
        var detected = result(DetectionSignal.DETECTED, 0.3);
        var weak = result(DetectionSignal.WEAK, 0.9);
        DetectionResult r = ResultCollectionStrategy.ACCUMULATE
                .resolve(List.of(detected, weak), "g1");
        assertThat(r.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(r.confidence()).isEqualTo(0.9);
    }

    @Test
    void accumulateMergesEvidence() {
        var r1 = result(DetectionSignal.DETECTED, 0.8, Map.of("key1", "val1"));
        var r2 = result(DetectionSignal.DETECTED, 0.6, Map.of("key2", "val2"));
        DetectionResult r = ResultCollectionStrategy.ACCUMULATE
                .resolve(List.of(r1, r2), "g1");
        assertThat(r.evidence()).containsEntry("key1", "val1")
                .containsEntry("key2", "val2");
    }

    @Test
    void accumulateEvidenceCollisionLastWins() {
        var r1 = result(DetectionSignal.DETECTED, 0.5, Map.of("k", "first"));
        var r2 = result(DetectionSignal.DETECTED, 0.5, Map.of("k", "second"));
        DetectionResult r = ResultCollectionStrategy.ACCUMULATE
                .resolve(List.of(r1, r2), "g1");
        assertThat(r.evidence()).containsEntry("k", "second");
    }

    @Test
    void accumulateUsesProvidedGanglionId() {
        var r1 = result(DetectionSignal.DETECTED, 0.8);
        DetectionResult r = ResultCollectionStrategy.ACCUMULATE
                .resolve(List.of(r1), "override-id");
        assertThat(r.ganglionId()).isEqualTo("override-id");
    }
}
