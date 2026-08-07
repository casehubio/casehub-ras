package io.casehub.ras.runtime;

import io.casehub.ras.api.FeedbackConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSuppressionStrategyTest {

    private final DefaultSuppressionStrategy strategy = new DefaultSuppressionStrategy();
    private final FeedbackConfig config = new FeedbackConfig(
            Set.of("dismissed"), Set.of("escalated"),
            Duration.ofHours(6), 0.1, Duration.ofDays(90), false);

    @Test void suppressesWithinCooldown() {
        Instant recent = Instant.now().minusSeconds(60);
        assertTrue(strategy.shouldSuppress("s1", "k1", "t1", config, Optional.of(recent)));
    }

    @Test void doesNotSuppressOutsideCooldown() {
        Instant old = Instant.now().minus(Duration.ofHours(7));
        assertFalse(strategy.shouldSuppress("s1", "k1", "t1", config, Optional.of(old)));
    }

    @Test void doesNotSuppressWhenNoDismissal() {
        assertFalse(strategy.shouldSuppress("s1", "k1", "t1", config, Optional.empty()));
    }

    @Test void exactCooldownBoundaryDoesNotSuppress() {
        Instant exactBoundary = Instant.now().minus(Duration.ofHours(6));
        assertFalse(strategy.shouldSuppress("s1", "k1", "t1", config, Optional.of(exactBoundary)));
    }
}
