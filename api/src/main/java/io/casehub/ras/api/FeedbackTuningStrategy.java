package io.casehub.ras.api;

import java.util.Optional;
import java.util.OptionalDouble;

public interface FeedbackTuningStrategy {

    OptionalDouble adjustThreshold(OutcomeStatistics statistics, double currentThreshold,
                                    FeedbackConfig config);

    Optional<double[]> adjustPriors(double[] currentPriors, long[] outcomeCounts,
                                     FeedbackConfig config);
}
