package io.casehub.ras.api;

import io.casehub.platform.api.expression.JQExpressionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GanglionDescriptorTest {

    @Test
    void naiveBayesRecordCarriesAllFields() {
        var feature = new GanglionDescriptor.NaiveBayes.Feature(
                new JQExpressionEvaluator(".data.severity"),
                List.of("LOW", "HIGH"),
                new double[][]{{0.8, 0.2}, {0.3, 0.7}});

        var mapping = new GanglionDescriptor.NaiveBayes.SignalMapping(
                "ANOMALY", 0.75, 0.30, 0.05);

        var descriptor = new GanglionDescriptor.NaiveBayes(
                "bayes-1",
                Set.of("sensor.reading"),
                List.of("NORMAL", "ANOMALY"),
                new double[]{0.9, 0.1},
                Map.of("severity", feature),
                mapping,
                Map.of(),
                Map.of());

        assertThat(descriptor.ganglionId()).isEqualTo("bayes-1");
        assertThat(descriptor.handledEventTypes()).containsExactly("sensor.reading");
        assertThat(descriptor.outcomes()).containsExactly("NORMAL", "ANOMALY");
        assertThat(descriptor.priors()).containsExactly(0.9, 0.1);
        assertThat(descriptor.features()).containsKey("severity");
        assertThat(descriptor.signalMapping().targetOutcome()).isEqualTo("ANOMALY");
        assertThat(descriptor.signalMapping().antiThreshold()).isEqualTo(0.05);
        assertThat(descriptor.evidenceTemplates()).isEmpty();}

    @Test
    void signalMappingWithNullAntiThreshold() {
        var mapping = new GanglionDescriptor.NaiveBayes.SignalMapping(
                "ANOMALY", 0.75, 0.30, null);

        assertThat(mapping.antiThreshold()).isNull();
    }

    @Test
    void expressionRulesRecordCarriesAllFields() {
        var rule = new GanglionDescriptor.ExpressionRules.Rule(
                new JQExpressionEvaluator(".data.severity == \"HIGH\""),
                DetectionSignal.DETECTED, 0.9, null, Map.of());
        var otherwise = new GanglionDescriptor.ExpressionRules.Rule(
                null, DetectionSignal.NOISE, 0.0, null, Map.of());

        var descriptor = new GanglionDescriptor.ExpressionRules(
                "severity-checker", Set.of("sensor.reading"),
                List.of(rule, otherwise), Map.of());

        assertThat(descriptor.ganglionId()).isEqualTo("severity-checker");
        assertThat(descriptor.handledEventTypes()).containsExactly("sensor.reading");
        assertThat(descriptor.rules()).hasSize(2);
        assertThat(descriptor.rules().get(0).when()).isNotNull();
        assertThat(descriptor.rules().get(0).signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(descriptor.rules().get(0).confidence()).isEqualTo(0.9);
        assertThat(descriptor.rules().get(0).confidenceExpression()).isNull();
        assertThat(descriptor.rules().get(1).when()).isNull();
        assertThat(descriptor.evidenceTemplates()).isEmpty();
    }

    @Test
    void expressionRulesWithEvidenceTemplates() {
        var descriptor = new GanglionDescriptor.ExpressionRules(
                "checker", Set.of("event.type"),
                List.of(new GanglionDescriptor.ExpressionRules.Rule(null, DetectionSignal.NOISE, 0.0, null, Map.of())),
                Map.of("severity", new JQExpressionEvaluator(".data.severity")));

        assertThat(descriptor.evidenceTemplates()).containsKey("severity");
    }

    @Test
    void ruleWithEvidenceTemplates() {
        var rule = new GanglionDescriptor.ExpressionRules.Rule(
                new JQExpressionEvaluator(".data.severity == \"HIGH\""),
                DetectionSignal.DETECTED, 0.9, null,
                Map.of("reason", new JQExpressionEvaluator(".data.reason")));
        assertThat(rule.evidenceTemplates()).containsKey("reason");
    }

    @Test
    void ruleWithConfidenceExpression() {
        var rule = new GanglionDescriptor.ExpressionRules.Rule(
                new JQExpressionEvaluator(".data.severity == \"HIGH\""),
                DetectionSignal.DETECTED, 0.5,
                new JQExpressionEvaluator(".data.score / 100"),
                Map.of());
        assertThat(rule.confidenceExpression()).isNotNull();
        assertThat(rule.confidence()).isEqualTo(0.5);
    }


    @Test
    void naiveBayesWithEvidenceTemplates() {
        var feature = new GanglionDescriptor.NaiveBayes.Feature(
                new JQExpressionEvaluator(".data.severity"),
                List.of("LOW", "HIGH"),
                new double[][]{{0.8, 0.2}, {0.3, 0.7}});
        var mapping = new GanglionDescriptor.NaiveBayes.SignalMapping(
                "ANOMALY", 0.75, 0.30, null);

        var descriptor = new GanglionDescriptor.NaiveBayes(
                "bayes-1", Set.of("sensor.reading"),
                List.of("NORMAL", "ANOMALY"), new double[]{0.9, 0.1},
                Map.of("severity", feature), mapping,
                Map.of("raw_sev", new JQExpressionEvaluator(".data.severity")),
                Map.of());

        assertThat(descriptor.evidenceTemplates()).containsKey("raw_sev");
    }

    @Test
    void naiveBayesWithOutcomeEvidenceTemplates() {
        var feature = new GanglionDescriptor.NaiveBayes.Feature(
                new JQExpressionEvaluator(".data.severity"),
                List.of("LOW", "HIGH"),
                new double[][]{{0.8, 0.2}, {0.3, 0.7}});
        var mapping = new GanglionDescriptor.NaiveBayes.SignalMapping(
                "ANOMALY", 0.75, 0.30, null);
        var descriptor = new GanglionDescriptor.NaiveBayes(
                "bayes-1", Set.of("sensor.reading"),
                List.of("NORMAL", "ANOMALY"), new double[]{0.9, 0.1},
                Map.of("severity", feature), mapping, Map.of(),
                Map.of("ANOMALY", Map.of("type", new JQExpressionEvaluator(".data.anomalyType"))));
        assertThat(descriptor.outcomeEvidenceTemplates()).containsKey("ANOMALY");
        assertThat(descriptor.outcomeEvidenceTemplates().get("ANOMALY")).containsKey("type");
    }


    @Test
    void situationWatcher_handledEventTypes_derived_from_mapping() {
        var mapping = Map.of(
                SituationChangeEvent.ChangeType.TRIGGERED, DetectionSignal.DETECTED,
                SituationChangeEvent.ChangeType.RESOLVED, DetectionSignal.ANTI);
        var watcher = new GanglionDescriptor.SituationWatcher("watcher-1", mapping, Map.of());
        assertThat(watcher.ganglionId()).isEqualTo("watcher-1");
        assertThat(watcher.handledEventTypes()).containsExactlyInAnyOrder(
                "ras.situation.triggered", "ras.situation.resolved");
    }

    @Test
    void situationWatcher_single_mapping() {
        var mapping = Map.of(
                SituationChangeEvent.ChangeType.TRIGGERED, DetectionSignal.DETECTED);
        var watcher = new GanglionDescriptor.SituationWatcher("watcher-2", mapping, Map.of());
        assertThat(watcher.handledEventTypes()).containsExactly("ras.situation.triggered");
    }

    @Test
    void situationWatcher_with_evidence_templates() {
        var mapping = Map.of(
                SituationChangeEvent.ChangeType.TRIGGERED, DetectionSignal.DETECTED);
        var templates = Map.of("detail", (io.casehub.platform.api.expression.ExpressionEvaluator)
                                                 new JQExpressionEvaluator(".data.detail"));
        var watcher = new GanglionDescriptor.SituationWatcher("watcher-3", mapping, templates);
        assertThat(watcher.evidenceTemplates()).containsKey("detail");
    }

    @Test
    void sealedInterfacePermitsAllVariants() {
        assertThat(GanglionDescriptor.class.isSealed()).isTrue();
        assertThat(GanglionDescriptor.class.getPermittedSubclasses())
                .hasSize(3)
                .extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder("NaiveBayes", "ExpressionRules", "SituationWatcher");
    }
}
