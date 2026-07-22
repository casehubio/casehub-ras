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
                DetectionSignal.DETECTED, 0.9, Map.of());
        var otherwise = new GanglionDescriptor.ExpressionRules.Rule(
                null, DetectionSignal.NOISE, 0.0, Map.of());

        var descriptor = new GanglionDescriptor.ExpressionRules(
                "severity-checker", Set.of("sensor.reading"),
                List.of(rule, otherwise), Map.of());

        assertThat(descriptor.ganglionId()).isEqualTo("severity-checker");
        assertThat(descriptor.handledEventTypes()).containsExactly("sensor.reading");
        assertThat(descriptor.rules()).hasSize(2);
        assertThat(descriptor.rules().get(0).when()).isNotNull();
        assertThat(descriptor.rules().get(0).signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(descriptor.rules().get(0).confidence()).isEqualTo(0.9);
        assertThat(descriptor.rules().get(1).when()).isNull();
        assertThat(descriptor.evidenceTemplates()).isEmpty();
    }

    @Test
    void expressionRulesWithEvidenceTemplates() {
        var descriptor = new GanglionDescriptor.ExpressionRules(
                "checker", Set.of("event.type"),
                List.of(new GanglionDescriptor.ExpressionRules.Rule(null, DetectionSignal.NOISE, 0.0, Map.of())),
                Map.of("severity", new JQExpressionEvaluator(".data.severity")));

        assertThat(descriptor.evidenceTemplates()).containsKey("severity");
    }

    @Test
    void ruleWithEvidenceTemplates() {
        var rule = new GanglionDescriptor.ExpressionRules.Rule(
                new JQExpressionEvaluator(".data.severity == \"HIGH\""),
                DetectionSignal.DETECTED, 0.9,
                Map.of("reason", new JQExpressionEvaluator(".data.reason")));
        assertThat(rule.evidenceTemplates()).containsKey("reason");
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
    void sealedInterfacePermitsNaiveBayesAndExpressionRules() {
        assertThat(GanglionDescriptor.class.isSealed()).isTrue();
        assertThat(GanglionDescriptor.class.getPermittedSubclasses())
                .hasSize(2)
                .extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder("NaiveBayes", "ExpressionRules");
    }
}
