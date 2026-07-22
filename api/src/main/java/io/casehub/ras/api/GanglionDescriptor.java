package io.casehub.ras.api;

import io.casehub.platform.api.expression.ExpressionEvaluator;

import java.util.List;
import java.util.Map;
import java.util.Set;

public sealed interface GanglionDescriptor {

    String ganglionId();

    Set<String> handledEventTypes();

    default Map<String, ExpressionEvaluator> evidenceTemplates() {return Map.of();}

    record NaiveBayes(
            String ganglionId,
            Set<String> handledEventTypes,
            List<String> outcomes,
            double[] priors,
            Map<String, Feature> features,
            SignalMapping signalMapping,
            Map<String, ExpressionEvaluator> evidenceTemplates,
            Map<String, Map<String, ExpressionEvaluator>> outcomeEvidenceTemplates
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

    record ExpressionRules(
            String ganglionId,
            Set<String> handledEventTypes,
            List<Rule> rules,
            Map<String, ExpressionEvaluator> evidenceTemplates
    ) implements GanglionDescriptor {

        public record Rule(
                ExpressionEvaluator when,
                DetectionSignal signal,
                double confidence,
                Map<String, ExpressionEvaluator> evidenceTemplates
        ) {}
    }
}
