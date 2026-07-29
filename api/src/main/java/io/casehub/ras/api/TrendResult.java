package io.casehub.ras.api;

public record TrendResult(
        long currentCount,
        long baselineCount,
        TrendDirection direction
) {
    public enum TrendDirection {RISING, FALLING, STABLE, INSUFFICIENT_DATA}

    public static TrendResult compute(long currentCount, long baselineCount,
                                      java.time.Duration window, java.time.Duration baseline) {
        if (baselineCount == 0) {
            return new TrendResult(currentCount, baselineCount, TrendDirection.INSUFFICIENT_DATA);
        }
        double currentRate  = (double) currentCount / window.toMillis();
        double baselineRate = (double) baselineCount / baseline.toMillis();
        double ratio        = currentRate / baselineRate;

        TrendDirection direction;
        if (ratio > 1.2) {
            direction = TrendDirection.RISING;
        } else if (ratio < 0.8) {
            direction = TrendDirection.FALLING;
        } else {
            direction = TrendDirection.STABLE;
        }
        return new TrendResult(currentCount, baselineCount, direction);
    }
}
