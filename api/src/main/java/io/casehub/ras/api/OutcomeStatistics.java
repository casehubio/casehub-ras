package io.casehub.ras.api;

import java.time.Instant;

public record OutcomeStatistics(
        String situationId,
        String tenancyId,
        long totalOutcomes,
        long noiseCount,
        long confirmedCount,
        long neutralCount,
        Instant windowStart
) implements QualityMetrics {
}
