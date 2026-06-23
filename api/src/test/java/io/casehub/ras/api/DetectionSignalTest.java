package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DetectionSignalTest {

    @Test
    void declarationOrderIsAscendingStrength() {
        assertThat(DetectionSignal.NOISE.ordinal())
                .isLessThan(DetectionSignal.ANTI.ordinal());
        assertThat(DetectionSignal.ANTI.ordinal())
                .isLessThan(DetectionSignal.WEAK.ordinal());
        assertThat(DetectionSignal.WEAK.ordinal())
                .isLessThan(DetectionSignal.DETECTED.ordinal());
    }

    @Test
    void isAtLeastReturnsTrueForSameOrStronger() {
        assertThat(DetectionSignal.DETECTED.isAtLeast(DetectionSignal.DETECTED)).isTrue();
        assertThat(DetectionSignal.DETECTED.isAtLeast(DetectionSignal.WEAK)).isTrue();
        assertThat(DetectionSignal.DETECTED.isAtLeast(DetectionSignal.ANTI)).isTrue();
        assertThat(DetectionSignal.DETECTED.isAtLeast(DetectionSignal.NOISE)).isTrue();
    }

    @Test
    void isAtLeastReturnsFalseForStrongerThreshold() {
        assertThat(DetectionSignal.NOISE.isAtLeast(DetectionSignal.ANTI)).isFalse();
        assertThat(DetectionSignal.NOISE.isAtLeast(DetectionSignal.WEAK)).isFalse();
        assertThat(DetectionSignal.ANTI.isAtLeast(DetectionSignal.WEAK)).isFalse();
        assertThat(DetectionSignal.WEAK.isAtLeast(DetectionSignal.DETECTED)).isFalse();
    }

    @Test
    void valuesArrayIsInAscendingStrengthOrder() {
        DetectionSignal[] values = DetectionSignal.values();
        assertThat(values).containsExactly(
                DetectionSignal.NOISE,
                DetectionSignal.ANTI,
                DetectionSignal.WEAK,
                DetectionSignal.DETECTED);
    }
}
