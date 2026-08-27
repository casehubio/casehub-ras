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
            Map<String, Map<String, ExpressionEvaluator>> outcomeEvidenceTemplates,
            Map<String, String> outcomeGroundTruth
    ) implements GanglionDescriptor {

        public NaiveBayes(String ganglionId, Set<String> handledEventTypes,
                          List<String> outcomes, double[] priors,
                          Map<String, Feature> features, SignalMapping signalMapping,
                          Map<String, ExpressionEvaluator> evidenceTemplates,
                          Map<String, Map<String, ExpressionEvaluator>> outcomeEvidenceTemplates) {
            this(ganglionId, handledEventTypes, outcomes, priors, features, signalMapping,
                 evidenceTemplates, outcomeEvidenceTemplates, null);
        }

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
                ExpressionEvaluator confidenceExpression,
                Map<String, ExpressionEvaluator> evidenceTemplates
        ) {}
    }

    record SituationWatcher(
            String ganglionId,
            Map<SituationChangeEvent.ChangeType, DetectionSignal> changeTypeMapping,
            Map<String, ExpressionEvaluator> evidenceTemplates
    ) implements GanglionDescriptor {

        private static final String EVENT_TYPE_PREFIX = "ras.situation.";

        @Override
        public Set<String> handledEventTypes() {
            return changeTypeMapping.keySet().stream()
                                    .map(ct -> EVENT_TYPE_PREFIX + ct.name().toLowerCase())
                                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

}
