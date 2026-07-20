package io.casehub.ras.api;

import io.casehub.platform.api.expression.ExpressionEvaluator;

import java.util.List;
import java.util.Map;
import java.util.Set;

public sealed interface GanglionDescriptor {

    String ganglionId();

    Set<String> handledEventTypes();

    record NaiveBayes(
            String ganglionId,
            Set<String> handledEventTypes,
            List<String> outcomes,
            double[] priors,
            Map<String, Feature> features,
            SignalMapping signalMapping
    ) implements GanglionDescriptor {

        public record Feature(
                ExpressionEvaluator expression,
                List<String> values,
                double[][] likelihoods
        ) {}

        public record SignalMapping(
                String targetOutcome,
                double detectedThreshold,
                double weakThreshold,
                Double antiThreshold
        ) {}
    }
}
