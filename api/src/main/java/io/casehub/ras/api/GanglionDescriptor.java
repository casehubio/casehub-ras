package io.casehub.ras.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.JQExpressionEvaluator;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = GanglionDescriptor.NaiveBayes.class, name = "naive-bayes"),
        @JsonSubTypes.Type(value = GanglionDescriptor.ExpressionRules.class, name = "expression-rules"),
        @JsonSubTypes.Type(value = GanglionDescriptor.SituationWatcher.class, name = "situation-watcher")
})
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

        public NaiveBayes {
            evidenceTemplates        = evidenceTemplates != null ? evidenceTemplates : Map.of();
            outcomeEvidenceTemplates = outcomeEvidenceTemplates != null ? outcomeEvidenceTemplates : Map.of();
            features                 = features != null ? features : Map.of();
        }

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
        ) {
            @JsonCreator
            public static Feature fromYaml(
                    @JsonProperty("expression") String expressionText,
                    @JsonProperty("language") String language,
                    @JsonProperty("values") List<String> values,
                    @JsonProperty("likelihoods") double[][] likelihoods) {
                Objects.requireNonNull(expressionText, "Feature missing required 'expression' field");
                Objects.requireNonNull(language, "Feature missing required 'language' field");
                ExpressionEvaluator evaluator = switch (language) {
                    case "jq" -> new JQExpressionEvaluator(expressionText);
                    case "mvel" -> new MvelExpressionEvaluator(expressionText);
                    default -> throw new IllegalArgumentException(
                            "Unknown expression language: " + language);
                };
                return new Feature(evaluator, values, likelihoods);
            }
        }

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

        public ExpressionRules {
            evidenceTemplates = evidenceTemplates != null ? evidenceTemplates : Map.of();
        }

        @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"otherwise"})
        public record Rule(
                ExpressionEvaluator when,
                DetectionSignal signal,
                double confidence,
                ExpressionEvaluator confidenceExpression,
                Map<String, ExpressionEvaluator> evidenceTemplates
        ) {
            public Rule {
                evidenceTemplates = evidenceTemplates != null ? evidenceTemplates : Map.of();
            }
        }
    }

    record SituationWatcher(
            String ganglionId,
            Map<SituationChangeEvent.ChangeType, DetectionSignal> changeTypeMapping,
            Map<String, ExpressionEvaluator> evidenceTemplates
    ) implements GanglionDescriptor {

        public SituationWatcher {
            evidenceTemplates = evidenceTemplates != null ? evidenceTemplates : Map.of();
        }

        private static final String EVENT_TYPE_PREFIX = "ras.situation.";

        @Override
        public Set<String> handledEventTypes() {
            return changeTypeMapping.keySet().stream()
                                    .map(ct -> EVENT_TYPE_PREFIX + ct.name().toLowerCase())
                                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

}
