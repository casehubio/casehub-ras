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
) {
    public double precision() {
        long decisive = confirmedCount + noiseCount;
        return decisive == 0 ? Double.NaN : (double) confirmedCount / decisive;
    }

    public double noiseRate() {
        return totalOutcomes == 0 ? Double.NaN : (double) noiseCount / totalOutcomes;
    }
}
