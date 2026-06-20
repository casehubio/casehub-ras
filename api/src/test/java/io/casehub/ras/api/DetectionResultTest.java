package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DetectionResultTest {

    @Test
    void validResultIsCreated() {
        var result = new DetectionResult("temp-ganglion", 0.85, DetectionSignal.DETECTED,
                Map.of("threshold", 95.0));

        assertThat(result.ganglionId()).isEqualTo("temp-ganglion");
        assertThat(result.confidence()).isEqualTo(0.85);
        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.evidence()).containsEntry("threshold", 95.0);
    }

    @Test
    void nullGanglionIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DetectionResult(null, 0.5, DetectionSignal.DETECTED, Map.of()))
                .withMessage("ganglionId");
    }

    @Test
    void nullSignalIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DetectionResult("g1", 0.5, null, Map.of()))
                .withMessage("signal");
    }

    @Test
    void confidenceBelowZeroIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DetectionResult("g1", -0.1, DetectionSignal.DETECTED, Map.of()))
                .withMessageContaining("-0.1");
    }

    @Test
    void confidenceAboveOneIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DetectionResult("g1", 1.01, DetectionSignal.DETECTED, Map.of()))
                .withMessageContaining("1.01");
    }

    @Test
    void confidenceBoundariesAreAccepted() {
        assertThatNoException().isThrownBy(
                () -> new DetectionResult("g1", 0.0, DetectionSignal.NOISE, Map.of()));
        assertThatNoException().isThrownBy(
                () -> new DetectionResult("g1", 1.0, DetectionSignal.DETECTED, Map.of()));
    }

    @Test
    void nullEvidenceNormalisedToEmptyMap() {
        var result = new DetectionResult("g1", 0.5, DetectionSignal.WEAK, null);
        assertThat(result.evidence()).isNotNull().isEmpty();
    }

    @Test
    void evidenceIsDefensivelyCopied() {
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("key", "value");
        var result = new DetectionResult("g1", 0.5, DetectionSignal.DETECTED, mutable);
        mutable.put("extra", "should-not-appear");
        assertThat(result.evidence()).doesNotContainKey("extra");
    }
}
