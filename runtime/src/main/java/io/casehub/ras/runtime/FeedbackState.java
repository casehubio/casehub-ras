package io.casehub.ras.runtime;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@ApplicationScoped
public class FeedbackState {

    private static final Logger LOG = Logger.getLogger(FeedbackState.class.getName());

    private final ConcurrentHashMap<StateKey, Double> thresholdOverrides = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<StateKey, double[]> priorOverrides = new ConcurrentHashMap<>();

    private record StateKey(String id, String tenancyId) {}

    public OptionalDouble effectiveThreshold(String situationId, String tenancyId) {
        Double value = thresholdOverrides.get(new StateKey(situationId, tenancyId));
        return value != null ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    public Optional<double[]> adjustedLogPriors(String ganglionId, String tenancyId) {
        double[] logPriors = priorOverrides.get(new StateKey(ganglionId, tenancyId));
        return logPriors != null ? Optional.of(Arrays.copyOf(logPriors, logPriors.length)) : Optional.empty();
    }

    public double[] currentRawPriors(String ganglionId, String tenancyId, double[] basePriors) {
        return adjustedLogPriors(ganglionId, tenancyId)
                .map(FeedbackState::normalizeLogToRaw)
                .orElse(basePriors);
    }

    public void applyThresholdOverride(String situationId, String tenancyId, double threshold) {
        if (Double.isNaN(threshold) || threshold <= 0.0 || threshold > 1.0) {
            LOG.warning("Rejecting feedback threshold for situation '" + situationId
                    + "' tenant '" + tenancyId + "': " + threshold
                    + " — must be in (0.0, 1.0]");
            return;
        }
        thresholdOverrides.put(new StateKey(situationId, tenancyId), threshold);
    }

    public void applyPriorOverride(String ganglionId, String tenancyId, double[] rawPriors) {
        for (int i = 0; i < rawPriors.length; i++) {
            if (Double.isNaN(rawPriors[i]) || rawPriors[i] <= 0.0) {
                LOG.warning("Rejecting feedback priors for ganglion '" + ganglionId
                        + "' tenant '" + tenancyId + "': prior[" + i + "] = " + rawPriors[i]
                        + " — zero/negative priors make outcomes permanently impossible");
                return;
            }
        }
        priorOverrides.put(new StateKey(ganglionId, tenancyId),
                Arrays.stream(rawPriors).map(Math::log).toArray());
    }

    static double[] normalizeLogToRaw(double[] logP) {
        double max = logP[0];
        for (int i = 1; i < logP.length; i++) {
            if (logP[i] > max) max = logP[i];
        }
        double[] exp = new double[logP.length];
        double sum = 0;
        for (int i = 0; i < logP.length; i++) {
            exp[i] = Math.exp(logP[i] - max);
            sum += exp[i];
        }
        for (int i = 0; i < exp.length; i++) {
            exp[i] /= sum;
        }
        return exp;
    }
}
