package io.casehub.ras.runtime;

import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.OutcomeStatistics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTuningStrategyTest {

    private final DefaultTuningStrategy strategy = new DefaultTuningStrategy();
    private final FeedbackConfig config = new FeedbackConfig(
            Set.of("dismissed"), Set.of("escalated"),
            Duration.ofHours(6), 0.1, Duration.ofDays(90), true);

    @Test void thresholdIncreasesWhenOverSensitive() {
        var stats = new OutcomeStatistics("s1", "t1", 20, 15, 5, 0, Instant.now());
        OptionalDouble result = strategy.adjustThreshold(stats, 0.7, config);
        assertTrue(result.isPresent());
        assertTrue(result.getAsDouble() > 0.7);
    }

    @Test void thresholdUnchangedWhenStable() {
        var stats = new OutcomeStatistics("s1", "t1", 20, 5, 15, 0, Instant.now());
        OptionalDouble result = strategy.adjustThreshold(stats, 0.7, config);
        assertTrue(result.isEmpty() || result.getAsDouble() <= 0.7);
    }

    @Test void thresholdEmptyWhenInsufficientData() {
        var stats = new OutcomeStatistics("s1", "t1", 5, 4, 1, 0, Instant.now());
        assertTrue(strategy.adjustThreshold(stats, 0.7, config).isEmpty());
    }

    @Test void thresholdClampedToUpperBound() {
        var highLr = new FeedbackConfig(Set.of("dismissed"), Set.of("escalated"),
                Duration.ofHours(6), 1.0, Duration.ofDays(90), true);
        var stats = new OutcomeStatistics("s1", "t1", 100, 100, 0, 0, Instant.now());
        OptionalDouble result = strategy.adjustThreshold(stats, 0.99, highLr);
        assertTrue(result.isPresent());
        assertTrue(result.getAsDouble() <= 1.0);
    }

    @Test void thresholdClampedToLowerBound() {
        var stats = new OutcomeStatistics("s1", "t1", 20, 0, 20, 0, Instant.now());
        OptionalDouble result = strategy.adjustThreshold(stats, 0.1, config);
        if (result.isPresent()) {
            assertTrue(result.getAsDouble() > 0.0);
        }
    }

    @Test void priorsBlendTowardEmpirical() {
        double[] current = {0.1, 0.9};
        long[] counts = {8, 2};
        var result = strategy.adjustPriors(current, counts, config);
        assertTrue(result.isPresent());
        assertTrue(result.get()[0] > 0.1);
        assertTrue(result.get()[1] < 0.9);
        assertEquals(1.0, result.get()[0] + result.get()[1], 0.001);
    }

    @Test void priorsEmptyWhenInsufficientData() {
        double[] current = {0.1, 0.9};
        long[] counts = {2, 1};
        assertTrue(strategy.adjustPriors(current, counts, config).isEmpty());
    }

    @Test void priorsNeverZero() {
        double[] current = {0.5, 0.5};
        long[] counts = {100, 0};
        var result = strategy.adjustPriors(current, counts, config);
        assertTrue(result.isPresent());
        assertTrue(result.get()[0] > 0.0);
        assertTrue(result.get()[1] > 0.0);
    }

    @Test void priorsRenormalized() {
        double[] current = {0.3, 0.7};
        long[] counts = {5, 5};
        var result = strategy.adjustPriors(current, counts, config);
        assertTrue(result.isPresent());
        assertEquals(1.0, result.get()[0] + result.get()[1], 0.001);
    }
}
