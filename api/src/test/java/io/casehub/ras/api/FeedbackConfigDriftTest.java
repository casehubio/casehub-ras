package io.casehub.ras.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeedbackConfigDriftTest {

    @Test
    void nineArgConstructorStoresNewFields() {
        var config = new FeedbackConfig(
                Set.of("dismissed"), Set.of("escalated"),
                Duration.ofHours(6), 0.1, Duration.ofDays(90), true,
                0.4, 0.7, Duration.ofHours(2));
        assertEquals(0.4, config.overSensitiveThreshold());
        assertEquals(0.7, config.underSensitiveThreshold());
        assertEquals(Duration.ofHours(2), config.crossRefWindow());
    }

    @Test
    void sixArgConstructorDefaultsNewFields() {
        var config = new FeedbackConfig(
                Set.of("dismissed"), Set.of("escalated"),
                Duration.ofHours(6), 0.1, Duration.ofDays(90), false);
        assertEquals(0.5, config.overSensitiveThreshold());
        assertEquals(0.5, config.underSensitiveThreshold());
        assertEquals(Duration.ofHours(1), config.crossRefWindow());
    }

    @Test
    void overSensitiveThresholdRejectsZero() {
        assertThrows(IllegalArgumentException.class, () ->
                new FeedbackConfig(Set.of("n"), Set.of("c"),
                        Duration.ofHours(1), 0.1, Duration.ofDays(1), false,
                        0.0, 0.5, Duration.ofHours(1)));
    }

    @Test
    void overSensitiveThresholdRejectsAboveOne() {
        assertThrows(IllegalArgumentException.class, () ->
                new FeedbackConfig(Set.of("n"), Set.of("c"),
                        Duration.ofHours(1), 0.1, Duration.ofDays(1), false,
                        1.1, 0.5, Duration.ofHours(1)));
    }

    @Test
    void overSensitiveThresholdAcceptsOne() {
        var config = new FeedbackConfig(Set.of("n"), Set.of("c"),
                Duration.ofHours(1), 0.1, Duration.ofDays(1), false,
                1.0, 0.5, Duration.ofHours(1));
        assertEquals(1.0, config.overSensitiveThreshold());
    }

    @Test
    void underSensitiveThresholdRejectsZero() {
        assertThrows(IllegalArgumentException.class, () ->
                new FeedbackConfig(Set.of("n"), Set.of("c"),
                        Duration.ofHours(1), 0.1, Duration.ofDays(1), false,
                        0.5, 0.0, Duration.ofHours(1)));
    }

    @Test
    void crossRefWindowRejectsZero() {
        assertThrows(IllegalArgumentException.class, () ->
                new FeedbackConfig(Set.of("n"), Set.of("c"),
                        Duration.ofHours(1), 0.1, Duration.ofDays(1), false,
                        0.5, 0.5, Duration.ZERO));
    }

    @Test
    void crossRefWindowRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () ->
                new FeedbackConfig(Set.of("n"), Set.of("c"),
                        Duration.ofHours(1), 0.1, Duration.ofDays(1), false,
                        0.5, 0.5, Duration.ofSeconds(-1)));
    }
}
