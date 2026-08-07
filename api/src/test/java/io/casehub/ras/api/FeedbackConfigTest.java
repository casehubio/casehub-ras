package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackConfigTest {

    @Test
    void classifyNoise() {
        var config = validConfig();
        assertEquals(OutcomeClassification.NOISE, config.classify("dismissed"));
    }

    @Test
    void classifyConfirmed() {
        var config = validConfig();
        assertEquals(OutcomeClassification.CONFIRMED, config.classify("escalated"));
    }

    @Test
    void classifyNeutral() {
        var config = validConfig();
        assertEquals(OutcomeClassification.NEUTRAL, config.classify("unknown-label"));
    }

    @Test
    void disjointLabelsRequired() {
        assertThrows(IllegalArgumentException.class, () ->
                new FeedbackConfig(Set.of("dismissed"), Set.of("dismissed"),
                        Duration.ofHours(6), 0.1, Duration.ofDays(90), false));
    }

    @Test
    void cooldownMustBePositive() {
        assertThrows(IllegalArgumentException.class, () ->
                new FeedbackConfig(Set.of("dismissed"), Set.of("escalated"),
                        Duration.ZERO, 0.1, Duration.ofDays(90), false));
    }

    @Test
    void learningRateLowerBound() {
        assertThrows(IllegalArgumentException.class, () ->
                new FeedbackConfig(Set.of("dismissed"), Set.of("escalated"),
                        Duration.ofHours(6), 0.0, Duration.ofDays(90), false));
    }

    @Test
    void learningRateUpperBound() {
        assertThrows(IllegalArgumentException.class, () ->
                new FeedbackConfig(Set.of("dismissed"), Set.of("escalated"),
                        Duration.ofHours(6), 1.1, Duration.ofDays(90), false));
    }

    @Test
    void learningRateExactlyOneIsValid() {
        assertDoesNotThrow(() ->
                new FeedbackConfig(Set.of("dismissed"), Set.of("escalated"),
                        Duration.ofHours(6), 1.0, Duration.ofDays(90), false));
    }

    @Test
    void retentionMustBeCooldownOrGreater() {
        assertThrows(IllegalArgumentException.class, () ->
                new FeedbackConfig(Set.of("dismissed"), Set.of("escalated"),
                        Duration.ofHours(6), 0.1, Duration.ofHours(1), false));
    }

    @Test
    void retentionEqualToCooldownIsValid() {
        assertDoesNotThrow(() ->
                new FeedbackConfig(Set.of("dismissed"), Set.of("escalated"),
                        Duration.ofHours(6), 0.1, Duration.ofHours(6), false));
    }

    @Test
    void tuningEnabledDefaultsFalse() {
        var config = validConfig();
        assertFalse(config.tuningEnabled());
    }

    @Test
    void tuningEnabledTrue() {
        var config = new FeedbackConfig(Set.of("dismissed"), Set.of("escalated"),
                Duration.ofHours(6), 0.1, Duration.ofDays(90), true);
        assertTrue(config.tuningEnabled());
    }

    @Test
    void labelsAreDefensivelyCopied() {
        var noise = new java.util.HashSet<>(Set.of("dismissed"));
        var confirmed = new java.util.HashSet<>(Set.of("escalated"));
        var config = new FeedbackConfig(noise, confirmed,
                Duration.ofHours(6), 0.1, Duration.ofDays(90), false);
        assertThrows(UnsupportedOperationException.class,
                () -> config.noiseLabels().add("injected"));
        assertThrows(UnsupportedOperationException.class,
                () -> config.confirmedLabels().add("injected"));
    }

    private FeedbackConfig validConfig() {
        return new FeedbackConfig(Set.of("dismissed", "false-positive"),
                Set.of("escalated", "confirmed"), Duration.ofHours(6), 0.1,
                Duration.ofDays(90), false);
    }
}
