package io.casehub.ras.api;

public interface QualityMetrics {

    long totalOutcomes();

    long noiseCount();

    long confirmedCount();

    default double precision() {
        long decisive = confirmedCount() + noiseCount();
        return decisive == 0 ? Double.NaN : (double) confirmedCount() / decisive;
    }

    default double noiseRate() {
        return totalOutcomes() == 0 ? Double.NaN : (double) noiseCount() / totalOutcomes();
    }
}
