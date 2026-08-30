package io.casehub.ras.api;

public record GanglionOutcomeStatistics(
        String ganglionId,
        long totalOutcomes,
        long noiseCount,
        long confirmedCount,
        long neutralCount,
        long missedCount
) implements QualityMetrics {

    public GanglionOutcomeStatistics(String ganglionId, long totalOutcomes,
                                      long noiseCount, long confirmedCount,
                                      long neutralCount) {
        this(ganglionId, totalOutcomes, noiseCount, confirmedCount, neutralCount, 0);
    }

    public double recall() {
        if (missedCount == 0) return Double.NaN;
        long decisive = confirmedCount + missedCount;
        return confirmedCount / (double) decisive;
    }
}
