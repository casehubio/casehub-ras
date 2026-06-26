package io.casehub.ras.runtime;

import java.util.Objects;

public record NaiveBayesSignalMapping(
        String targetOutcome,
        double detectedThreshold,
        double weakThreshold,
        Double antiThreshold
) {
    public NaiveBayesSignalMapping {
        Objects.requireNonNull(targetOutcome, "targetOutcome");
        if (Double.isNaN(detectedThreshold) || detectedThreshold <= weakThreshold) {
            throw new IllegalArgumentException(
                    "detectedThreshold (" + detectedThreshold
                    + ") must be > weakThreshold (" + weakThreshold + ") and not NaN");
        }
        if (Double.isNaN(weakThreshold) || weakThreshold <= 0) {
            throw new IllegalArgumentException(
                    "weakThreshold must be > 0 and not NaN, got: " + weakThreshold);
        }
        if (detectedThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "detectedThreshold must be <= 1.0, got: " + detectedThreshold);
        }
        if (weakThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "weakThreshold must be <= 1.0, got: " + weakThreshold);
        }
        if (antiThreshold != null) {
            if (Double.isNaN(antiThreshold) || antiThreshold <= 0) {
                throw new IllegalArgumentException(
                        "antiThreshold must be > 0 and not NaN when set, got: " + antiThreshold);
            }
            if (antiThreshold >= weakThreshold) {
                throw new IllegalArgumentException(
                        "antiThreshold (" + antiThreshold
                        + ") must be < weakThreshold (" + weakThreshold + ")");
            }
        }
    }

    public NaiveBayesSignalMapping(String targetOutcome, double detectedThreshold,
                                   double weakThreshold) {
        this(targetOutcome, detectedThreshold, weakThreshold, null);
    }
}
