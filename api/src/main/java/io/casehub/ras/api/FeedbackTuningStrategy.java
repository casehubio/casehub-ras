package io.casehub.ras.api;

import java.util.Optional;
import java.util.OptionalDouble;

public interface FeedbackTuningStrategy {

    OptionalDouble adjustThreshold(OutcomeStatistics statistics, double currentThreshold,
                                    FeedbackConfig config);

    Optional<double[]> adjustPriors(double[] currentPriors, long[] outcomeCounts,
                                     FeedbackConfig config);

    default DriftDirection classifyDrift(OutcomeStatistics statistics, FeedbackConfig config) {
        int MIN_DRIFT_OUTCOMES = 10;
        int MIN_RECALL_SAMPLES = 3;

        if (statistics.totalOutcomes() < MIN_DRIFT_OUTCOMES) {
            return DriftDirection.INSUFFICIENT_DATA;
        }

        boolean overSensitive = !Double.isNaN(statistics.noiseRate())
                && statistics.noiseRate() > config.overSensitiveThreshold();

        boolean underSensitive = false;
        double recall = statistics.recall();
        if (!Double.isNaN(recall)
                && (statistics.confirmedCount() + statistics.missedCount()) >= MIN_RECALL_SAMPLES
                && recall < config.underSensitiveThreshold()) {
            underSensitive = true;
        }

        if (overSensitive && underSensitive) return DriftDirection.BOTH_DRIFTING;
        if (overSensitive) return DriftDirection.OVER_SENSITIVE;
        if (underSensitive) return DriftDirection.UNDER_SENSITIVE;
        return DriftDirection.STABLE;
    }
}
