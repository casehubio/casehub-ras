package io.casehub.ras.runtime;

import io.casehub.platform.api.expression.CompiledExpression;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@SuppressWarnings("rawtypes")
public record NaiveBayesConfig(
        String ganglionId,
        Set<String> handledEventTypes,
        List<String> outcomes,
        double[] priors,
        Map<String, FeatureLikelihood> features,
        NaiveBayesFeatureExtractor featureExtractor,
        NaiveBayesSignalMapping signalMapping,
        Map<String, Map<String, CompiledExpression<Map, Object>>> outcomeEvidenceTemplates
) {
    public NaiveBayesConfig {
        Objects.requireNonNull(ganglionId, "ganglionId");
        if (handledEventTypes == null || handledEventTypes.isEmpty()) {
            throw new IllegalArgumentException("handledEventTypes must not be empty");
        }
        handledEventTypes = Set.copyOf(handledEventTypes);
        if (outcomes == null || outcomes.size() < 2) {
            throw new IllegalArgumentException("outcomes must have at least 2 entries");
        }
        outcomes = List.copyOf(outcomes);
        Objects.requireNonNull(priors, "priors");
        if (priors.length != outcomes.size()) {
            throw new IllegalArgumentException(
                    "priors length (" + priors.length
                    + ") must match outcomes size (" + outcomes.size() + ")");
        }
        priors = Arrays.copyOf(priors, priors.length);
        for (int i = 0; i < priors.length; i++) {
            if (Double.isNaN(priors[i]) || priors[i] <= 0.0) {
                throw new IllegalArgumentException(
                        "priors[" + i + "] must be > 0.0 and not NaN, got: " + priors[i]
                        + " — zero priors make outcomes permanently impossible");
            }
        }
        double sum = Arrays.stream(priors).sum();
        if (Math.abs(sum - 1.0) > 1e-6) {
            throw new IllegalArgumentException("priors must sum to 1.0, got: " + sum);
        }
        features = Map.copyOf(features);
        for (var entry : features.entrySet()) {
            if (entry.getValue().likelihoods().length != outcomes.size()) {
                throw new IllegalArgumentException(
                        "Feature '" + entry.getKey() + "' has "
                        + entry.getValue().likelihoods().length
                        + " likelihood rows but there are " + outcomes.size() + " outcomes");
            }
        }
        Objects.requireNonNull(featureExtractor, "featureExtractor");
        Objects.requireNonNull(signalMapping, "signalMapping");
        int targetIndex = outcomes.indexOf(signalMapping.targetOutcome());
        if (targetIndex < 0) {
            throw new IllegalArgumentException(
                    "targetOutcome '" + signalMapping.targetOutcome() + "' not in outcomes");
        }
        outcomeEvidenceTemplates = outcomeEvidenceTemplates != null
                                   ? Map.copyOf(outcomeEvidenceTemplates) : Map.of();
        for (String outcomeKey : outcomeEvidenceTemplates.keySet()) {
            if (!outcomes.contains(outcomeKey)) {
                throw new IllegalArgumentException(
                        "outcomeEvidenceTemplates key '" + outcomeKey
                        + "' is not in outcomes " + outcomes);
            }
        }
    }
}
