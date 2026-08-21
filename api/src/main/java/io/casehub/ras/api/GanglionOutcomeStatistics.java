package io.casehub.ras.api;

public record GanglionOutcomeStatistics(
        String ganglionId,
        long totalOutcomes,
        long noiseCount,
        long confirmedCount,
        long neutralCount
) implements QualityMetrics {
}
