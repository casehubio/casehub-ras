package io.casehub.ras.runtime;

import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.FeedbackTuningStrategy;
import io.casehub.ras.api.OutcomeStatistics;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.OptionalDouble;

@ApplicationScoped
@DefaultBean
public class DefaultTuningStrategy implements FeedbackTuningStrategy {

    private static final int MIN_OUTCOMES_THRESHOLD = 10;
    private static final int MIN_OUTCOMES_PRIORS = 5;

    @Override
    public OptionalDouble adjustThreshold(OutcomeStatistics statistics, double currentThreshold,
                                           FeedbackConfig config) {
        if (statistics.totalOutcomes() < MIN_OUTCOMES_THRESHOLD) return OptionalDouble.empty();

        double noiseRate = statistics.noiseRate();
        if (Double.isNaN(noiseRate) || noiseRate <= 0.5) return OptionalDouble.empty();

        double adjustment = config.learningRate() * (noiseRate - 0.5);
        double adjusted = currentThreshold + adjustment;
        adjusted = Math.max(Double.MIN_VALUE, Math.min(1.0, adjusted));
        return OptionalDouble.of(adjusted);
    }

    @Override
    public Optional<double[]> adjustPriors(double[] currentPriors, long[] outcomeCounts,
                                            FeedbackConfig config) {
        long total = 0;
        for (long c : outcomeCounts) total += c;
        if (total < MIN_OUTCOMES_PRIORS) return Optional.empty();

        int n = currentPriors.length;
        double[] empirical = new double[n];
        double smoothedTotal = total + n;
        for (int i = 0; i < n; i++) {
            empirical[i] = (outcomeCounts[i] + 1.0) / smoothedTotal;
        }

        double lr = config.learningRate();
        double[] blended = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            blended[i] = (1 - lr) * currentPriors[i] + lr * empirical[i];
            sum += blended[i];
        }
        for (int i = 0; i < n; i++) {
            blended[i] /= sum;
        }
        return Optional.of(blended);
    }
}
