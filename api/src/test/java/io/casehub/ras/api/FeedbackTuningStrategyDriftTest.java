package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeedbackTuningStrategyDriftTest {

    private final FeedbackTuningStrategy strategy = new FeedbackTuningStrategy() {
        @Override
        public OptionalDouble adjustThreshold(OutcomeStatistics s, double t, FeedbackConfig c) {
            return OptionalDouble.empty();
        }

        @Override
        public Optional<double[]> adjustPriors(double[] p, long[] o, FeedbackConfig c) {
            return Optional.empty();
        }
    };

    private FeedbackConfig config(double overThreshold, double underThreshold) {
        return new FeedbackConfig(Set.of("n"), Set.of("c"),
                Duration.ofHours(1), 0.1, Duration.ofDays(90), false,
                overThreshold, underThreshold, Duration.ofHours(1));
    }

    private OutcomeStatistics stats(long total, long noise, long confirmed,
                                     long neutral, long missed) {
        return new OutcomeStatistics("sit", "t1", total, noise, confirmed,
                neutral, Instant.EPOCH, missed);
    }

    @Test
    void insufficientData_belowMinOutcomes() {
        assertEquals(DriftDirection.INSUFFICIENT_DATA,
                strategy.classifyDrift(stats(9, 5, 2, 2, 0), config(0.5, 0.5)));
    }

    @Test
    void stable_bothWithinBounds() {
        // noise=5/20=0.25 < 0.5, recall=10/(10+2)=0.83 > 0.5
        assertEquals(DriftDirection.STABLE,
                strategy.classifyDrift(stats(20, 5, 10, 5, 2), config(0.5, 0.5)));
    }

    @Test
    void overSensitive_highNoiseRate() {
        // noise=12/20=0.6 > 0.5, recall=8/(8+1)=0.89 > 0.5
        assertEquals(DriftDirection.OVER_SENSITIVE,
                strategy.classifyDrift(stats(20, 12, 8, 0, 1), config(0.5, 0.5)));
    }

    @Test
    void underSensitive_lowRecall() {
        // noise=2/20=0.1 < 0.5, recall=3/(3+5)=0.375 < 0.5
        assertEquals(DriftDirection.UNDER_SENSITIVE,
                strategy.classifyDrift(stats(20, 2, 3, 15, 5), config(0.5, 0.5)));
    }

    @Test
    void bothDrifting_highNoiseAndLowRecall() {
        // noise=12/20=0.6 > 0.5, recall=2/(2+5)=0.286 < 0.5
        assertEquals(DriftDirection.BOTH_DRIFTING,
                strategy.classifyDrift(stats(20, 12, 2, 6, 5), config(0.5, 0.5)));
    }

    @Test
    void recallNaN_noConfirmedNoMissed_notUnderSensitive() {
        // noise=12/20=0.6 > 0.5, recall=NaN (0 confirmed, 0 missed)
        assertEquals(DriftDirection.OVER_SENSITIVE,
                strategy.classifyDrift(stats(20, 12, 0, 8, 0), config(0.5, 0.5)));
    }

    @Test
    void minRecallSamples_belowThreshold() {
        // recall=1/(1+1)=0.5 but confirmed+missed=2 < MIN_RECALL_SAMPLES(3)
        assertEquals(DriftDirection.STABLE,
                strategy.classifyDrift(stats(20, 5, 1, 14, 1), config(0.5, 0.6)));
    }

    @Test
    void configurableThresholds_overSensitive() {
        // noise=4/20=0.2, overThreshold=0.15 → over-sensitive
        assertEquals(DriftDirection.OVER_SENSITIVE,
                strategy.classifyDrift(stats(20, 4, 10, 6, 3), config(0.15, 0.5)));
    }

    @Test
    void configurableThresholds_underSensitive() {
        // recall=10/(10+3)=0.77, underThreshold=0.8 → under-sensitive
        assertEquals(DriftDirection.UNDER_SENSITIVE,
                strategy.classifyDrift(stats(20, 1, 10, 9, 3), config(0.5, 0.8)));
    }

    @Test
    void exactlyAtMinOutcomes_notInsufficientData() {
        // totalOutcomes=10 is exactly at MIN_DRIFT_OUTCOMES — should NOT be INSUFFICIENT_DATA
        assertEquals(DriftDirection.STABLE,
                strategy.classifyDrift(stats(10, 2, 5, 3, 0), config(0.5, 0.5)));
    }

    @Test
    void recallExactlyAtThreshold_notUnderSensitive() {
        // recall = 3/(3+3) = 0.5, underThreshold = 0.5 → NOT under-sensitive (strictly less)
        assertEquals(DriftDirection.STABLE,
                strategy.classifyDrift(stats(20, 2, 3, 15, 3), config(0.5, 0.5)));
    }

    @Test
    void noiseRateExactlyAtThreshold_notOverSensitive() {
        // noiseRate = 10/20 = 0.5, overThreshold = 0.5 → NOT over-sensitive (strictly greater)
        assertEquals(DriftDirection.STABLE,
                strategy.classifyDrift(stats(20, 10, 5, 5, 3), config(0.5, 0.5)));
    }
}
