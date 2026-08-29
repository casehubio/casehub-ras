package io.casehub.ras.api;

import java.time.Instant;

public record OutcomeStatistics(
        String situationId,
        String tenancyId,
        long totalOutcomes,
        long noiseCount,
        long confirmedCount,
        long neutralCount,
        Instant windowStart,
        long missedCount
) implements QualityMetrics {

    public OutcomeStatistics(String situationId, String tenancyId, long totalOutcomes,
                             long noiseCount, long confirmedCount, long neutralCount,
                             Instant windowStart) {
        this(situationId, tenancyId, totalOutcomes, noiseCount, confirmedCount,
             neutralCount, windowStart, 0);
    }

    public double recall() {
        long decisive = confirmedCount + missedCount;
        return decisive == 0 ? Double.NaN : confirmedCount / (double) decisive;
    }
}
