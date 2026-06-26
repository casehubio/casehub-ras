package io.casehub.ras.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class NaiveBayesSignalMappingTest {

    @Test
    void validMappingWithAntiThreshold() {
        var mapping = new NaiveBayesSignalMapping("SUSPICIOUS", 0.75, 0.30, 0.05);
        assertThat(mapping.targetOutcome()).isEqualTo("SUSPICIOUS");
        assertThat(mapping.detectedThreshold()).isEqualTo(0.75);
        assertThat(mapping.weakThreshold()).isEqualTo(0.30);
        assertThat(mapping.antiThreshold()).isEqualTo(0.05);
    }

    @Test
    void validMappingWithoutAntiThreshold() {
        var mapping = new NaiveBayesSignalMapping("TARGET", 0.80, 0.40);
        assertThat(mapping.antiThreshold()).isNull();
    }

    @Test
    void nullTargetOutcomeIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new NaiveBayesSignalMapping(null, 0.75, 0.30));
    }

    @Test
    void detectedThresholdMustBeGreaterThanWeakThreshold() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesSignalMapping("T", 0.50, 0.50))
                .withMessageContaining("detectedThreshold");
    }

    @Test
    void detectedThresholdBelowWeakThresholdIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesSignalMapping("T", 0.30, 0.50));
    }

    @Test
    void weakThresholdMustBePositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesSignalMapping("T", 0.50, 0.0))
                .withMessageContaining("weakThreshold");
    }

    @Test
    void detectedThresholdAboveOneIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesSignalMapping("T", 1.01, 0.50))
                .withMessageContaining("detectedThreshold")
                .withMessageContaining("1.0");
    }

    @Test
    void weakThresholdAboveOneIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesSignalMapping("T", 1.0, 1.01))
                .withMessageContaining("weakThreshold");
    }

    @Test
    void nanDetectedThresholdIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesSignalMapping("T", Double.NaN, 0.30))
                .withMessageContaining("NaN");
    }

    @Test
    void nanWeakThresholdIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesSignalMapping("T", 0.75, Double.NaN))
                .withMessageContaining("NaN");
    }

    @Test
    void antiThresholdMustBePositiveWhenSet() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesSignalMapping("T", 0.75, 0.30, 0.0))
                .withMessageContaining("antiThreshold");
    }

    @Test
    void antiThresholdMustBeLessThanWeakThreshold() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesSignalMapping("T", 0.75, 0.30, 0.30))
                .withMessageContaining("antiThreshold")
                .withMessageContaining("weakThreshold");
    }

    @Test
    void nanAntiThresholdIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NaiveBayesSignalMapping("T", 0.75, 0.30, Double.NaN))
                .withMessageContaining("NaN");
    }

    @Test
    void boundaryDetectedEqualsOne() {
        assertThatNoException()
                .isThrownBy(() -> new NaiveBayesSignalMapping("T", 1.0, 0.50));
    }
}
