package io.casehub.ras.runtime;

import io.casehub.platform.api.expression.JQExpressionEvaluator;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.DefaultCorrelationKeyExtractor;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.TriggerMode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlSituationDefinitionProviderTest {

    private YamlSituationDefinitionProvider provider(String yaml) {
        InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        return new YamlSituationDefinitionProvider(is);
    }

    @Test
    void parsesAndChainMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1, e2]
                    correlationWindow: PT5M
                    chainMode:
                      type: and
                      ganglia: [g1, g2]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: case1
                      caseVersion: "1.0"
                """).registrations();

        assertThat(regs).hasSize(1);
        var def = regs.get(0).definition();
        assertThat(def.situationId()).isEqualTo("sit1");
        assertThat(def.eventTypes()).containsExactlyInAnyOrder("e1", "e2");
        assertThat(def.correlationWindow()).isEqualTo(Duration.ofMinutes(5));
        assertThat(def.chainMode()).isInstanceOf(ChainMode.And.class);
        var and = (ChainMode.And) def.chainMode();
        assertThat(and.requiredGanglia()).containsExactlyInAnyOrder("g1", "g2");
    }

    @Test
    void parsesOrChainMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1, g2]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        assertThat(regs).hasSize(1);
        var def = regs.get(0).definition();
        assertThat(def.correlationWindow()).isNull();
        assertThat(def.chainMode()).isInstanceOf(ChainMode.Or.class);
        assertThat(((ChainMode.Or) def.chainMode()).ganglia()).containsExactlyInAnyOrder("g1", "g2");
    }

    @Test
    void parsesThresholdChainMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: threshold
                      ganglia: [g1, g2]
                      minConfidence: 0.8
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        var threshold = (ChainMode.Threshold) regs.get(0).definition().chainMode();
        assertThat(threshold.ganglia()).containsExactlyInAnyOrder("g1", "g2");
        assertThat(threshold.minConfidence()).isEqualTo(0.8);
    }

    @Test
    void parsesSequenceChainMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1, e2]
                    chainMode:
                      type: sequence
                      ganglia: [g1, g2, g3]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        var seq = (ChainMode.Sequence) regs.get(0).definition().chainMode();
        assertThat(seq.orderedGanglia()).containsExactly("g1", "g2", "g3");
    }

    @Test
    void parsesCountChainMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: count
                      ganglionId: g1
                      requiredCount: 5
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        var count = (ChainMode.Count) regs.get(0).definition().chainMode();
        assertThat(count.ganglionId()).isEqualTo("g1");
        assertThat(count.requiredCount()).isEqualTo(5);
    }

    @Test
    void parsesBaseCaseData() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                      baseCaseData:
                        priority: HIGH
                        severity: 5
                """).registrations();

        var config = ((TriggerAction.CreateCase) regs.get(0).definition().triggerAction()).config();
        assertThat(config.caseNamespace()).isEqualTo("ns");
        assertThat(config.baseCaseData()).containsEntry("priority", "HIGH");
        assertThat(config.baseCaseData()).containsEntry("severity", 5);
    }

    @Test
    void numericCaseVersionConvertedToString() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: 2.0
                """).registrations();

        assertThat(((TriggerAction.CreateCase) regs.get(0).definition().triggerAction()).config().caseVersion()).isEqualTo("2.0");
    }

    @Test
    void multipleSituationsParsed() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c1
                      caseVersion: "1"
                  - situationId: sit2
                    eventTypes: [e2]
                    chainMode:
                      type: or
                      ganglia: [g2]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c2
                      caseVersion: "1"
                """).registrations();

        assertThat(regs).hasSize(2);
        assertThat(regs.get(0).definition().situationId()).isEqualTo("sit1");
        assertThat(regs.get(1).definition().situationId()).isEqualTo("sit2");
    }

    @Test
    void emptyYamlReturnsEmptyList() {
        var regs = provider("").registrations();
        assertThat(regs).isEmpty();
    }

    @Test
    void noSituationsKeyReturnsEmptyList() {
        var regs = provider("other: value").registrations();
        assertThat(regs).isEmpty();
    }

    @Test
    void usesDefaultCorrelationKeyExtractor() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        assertThat(regs.get(0).correlationKeyExtractor())
                .isSameAs(DefaultCorrelationKeyExtractor.INSTANCE);
    }

    @Test
    void unknownChainModeTypeThrows() {
        assertThatThrownBy(() -> provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: unknown
                      ganglia: [g1]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void missingSituationIdThrows() {
        assertThatThrownBy(() -> provider("""
                situations:
                  - eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("situationId");
    }

    @Test
    void parsesEventBufferDelay() {
        var regs = provider("""
                situations:
                  - situationId: buffered-sit
                    eventTypes: [test.event]
                    correlationWindow: PT5M
                    eventBufferDelay: PT3S
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: case
                      caseVersion: "1.0"
                """).registrations();
        assertThat(regs).hasSize(1);
        assertThat(regs.get(0).definition().eventBufferDelay())
                .isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void absentEventBufferDelayIsNull() {
        var regs = provider("""
                situations:
                  - situationId: no-buffer
                    eventTypes: [test.event]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: case
                      caseVersion: "1.0"
                """).registrations();
        assertThat(regs).hasSize(1);
        assertThat(regs.get(0).definition().eventBufferDelay()).isNull();
    }

    @Test
    void missingChainModeThrows() {
        assertThatThrownBy(() -> provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chainMode");
    }

    @Test
    void missingTriggerActionThrows() {
        assertThatThrownBy(() -> provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                """).registrations())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("triggerAction");
    }

    @Test
    void parsesFireOnceTriggerMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                    triggerMode:
                      type: fire-once
                """).registrations();

        assertThat(regs).hasSize(1);
        var triggerMode = regs.get(0).definition().triggerMode();
        assertThat(triggerMode).isInstanceOf(TriggerMode.FireOnce.class);
    }

    @Test
    void parsesRepeatingTriggerMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                    triggerMode:
                      type: repeating
                      cooldown: PT5M
                """).registrations();

        assertThat(regs).hasSize(1);
        var triggerMode = regs.get(0).definition().triggerMode();
        assertThat(triggerMode).isInstanceOf(TriggerMode.Repeating.class);
        var repeating = (TriggerMode.Repeating) triggerMode;
        assertThat(repeating.cooldown()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void defaultsToFireOnceWhenTriggerModeAbsent() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        assertThat(regs).hasSize(1);
        var triggerMode = regs.get(0).definition().triggerMode();
        assertThat(triggerMode).isInstanceOf(TriggerMode.FireOnce.class);
    }

    @Test
    void parsesStreakChainMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: streak
                      ganglionId: g1
                      requiredCount: 3
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        var streak = (ChainMode.Streak) regs.get(0).definition().chainMode();
        assertThat(streak.ganglionId()).isEqualTo("g1");
        assertThat(streak.requiredCount()).isEqualTo(3);
    }

    @Test
    void parsesRateChainMode() {
        var regs = provider("""
                situations:
                  - situationId: sit1
                    eventTypes: [e1]
                    chainMode:
                      type: rate
                      ganglia: [g1, g2]
                      minRate: 0.6
                      windowSize: 10
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: c
                      caseVersion: "1"
                """).registrations();

        var rate = (ChainMode.Rate) regs.get(0).definition().chainMode();
        assertThat(rate.ganglia()).containsExactlyInAnyOrder("g1", "g2");
        assertThat(rate.minRate()).isEqualTo(0.6);
        assertThat(rate.windowSize()).isEqualTo(10);
    }

    @Test
    void parses_triggerAction_createCase() {
        String yaml = """
                situations:
                  - situationId: test-sit
                    eventTypes: [io.test.event]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: name
                      caseVersion: "1.0"
                """;
        var provider = new YamlSituationDefinitionProvider(
                new ByteArrayInputStream(yaml.getBytes()));
        var def = provider.registrations().getFirst().definition();
        assertThat(def.triggerAction()).isInstanceOf(TriggerAction.CreateCase.class);
        var createCase = (TriggerAction.CreateCase) def.triggerAction();
        assertThat(createCase.config().caseNamespace()).isEqualTo("ns");
    }

    @Test
    void parses_triggerAction_notifyOnly() {
        String yaml = """
                situations:
                  - situationId: test-sit
                    eventTypes: [io.test.event]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerAction:
                      type: notify-only
                """;
        var provider = new YamlSituationDefinitionProvider(
                new ByteArrayInputStream(yaml.getBytes()));
        var def = provider.registrations().getFirst().definition();
        assertThat(def.triggerAction()).isInstanceOf(TriggerAction.NotifyOnly.class);
    }

    @Test
    void rejects_missing_triggerAction() {
        String yaml = """
                situations:
                  - situationId: test-sit
                    eventTypes: [io.test.event]
                    chainMode:
                      type: or
                      ganglia: [g1]
                """;
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new YamlSituationDefinitionProvider(
                        new ByteArrayInputStream(yaml.getBytes())))
                .withMessageContaining("triggerAction");
    }

    @Test
    void rejects_unknown_triggerAction_type() {
        String yaml = """
                situations:
                  - situationId: test-sit
                    eventTypes: [io.test.event]
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerAction:
                      type: explode
                """;
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new YamlSituationDefinitionProvider(
                        new ByteArrayInputStream(yaml.getBytes())));
    }

    @Test
    void parsesCorrelationKeyExpression() {
        var regs = provider("""
                            situations:
                              - situationId: sit1
                                eventTypes: [e1]
                                correlationKey:
                                  expression: ".data.orderId"
                                  language: jq
                                chainMode:
                                  type: or
                                  ganglia: [g1]
                                triggerAction:
                                  type: create-case
                                  caseNamespace: ns
                                  caseName: c
                                  caseVersion: "1"
                            """).registrations();

        assertThat(regs).hasSize(1);
        var def = regs.get(0).definition();
        assertThat(def.correlationKeyExpression()).isNotNull();
        assertThat(def.correlationKeyExpression()).isInstanceOf(JQExpressionEvaluator.class);
        assertThat(((JQExpressionEvaluator) def.correlationKeyExpression()).expression())
                .isEqualTo(".data.orderId");
    }

    @Test
    void parsesEventFilterExpression() {
        var regs = provider("""
                            situations:
                              - situationId: sit1
                                eventTypes: [e1]
                                eventFilter:
                                  expression: "data.severity >= 3"
                                  language: mvel
                                chainMode:
                                  type: or
                                  ganglia: [g1]
                                triggerAction:
                                  type: create-case
                                  caseNamespace: ns
                                  caseName: c
                                  caseVersion: "1"
                            """).registrations();

        var def = regs.get(0).definition();
        assertThat(def.eventFilter()).isNotNull();
        assertThat(def.eventFilter()).isInstanceOf(MvelExpressionEvaluator.class);
        assertThat(((MvelExpressionEvaluator) def.eventFilter()).expression())
                .isEqualTo("data.severity >= 3");
    }

    @Test
    void parsesDynamicCaseData() {
        var regs = provider("""
                            situations:
                              - situationId: sit1
                                eventTypes: [e1]
                                dynamicCaseData:
                                  orderId:
                                    expression: ".correlationKey"
                                    language: jq
                                  severity:
                                    expression: ".detections[-1].result.evidence.severity"
                                    language: jq
                                chainMode:
                                  type: or
                                  ganglia: [g1]
                                triggerAction:
                                  type: create-case
                                  caseNamespace: ns
                                  caseName: c
                                  caseVersion: "1"
                            """).registrations();

        var def = regs.get(0).definition();
        assertThat(def.dynamicCaseData()).hasSize(2);
        assertThat(def.dynamicCaseData()).containsKey("orderId");
        assertThat(def.dynamicCaseData()).containsKey("severity");
        assertThat(def.dynamicCaseData().get("orderId")).isInstanceOf(JQExpressionEvaluator.class);
    }

    @Test
    void unknownExpressionLanguageThrows() {
        assertThatIllegalArgumentException().isThrownBy(() -> provider("""
                                                                       situations:
                                                                         - situationId: sit1
                                                                           eventTypes: [e1]
                                                                           correlationKey:
                                                                             expression: ".data.orderId"
                                                                             language: groovy
                                                                           chainMode:
                                                                             type: or
                                                                             ganglia: [g1]
                                                                           triggerAction:
                                                                             type: create-case
                                                                             caseNamespace: ns
                                                                             caseName: c
                                                                             caseVersion: "1"
                                                                       """).registrations())
                                            .withMessageContaining("groovy");
    }

    @Test
    void absentExpressionFieldsDefaultToNull() {
        var regs = provider("""
                            situations:
                              - situationId: sit1
                                eventTypes: [e1]
                                chainMode:
                                  type: or
                                  ganglia: [g1]
                                triggerAction:
                                  type: create-case
                                  caseNamespace: ns
                                  caseName: c
                                  caseVersion: "1"
                            """).registrations();

        var def = regs.get(0).definition();
        assertThat(def.correlationKeyExpression()).isNull();
        assertThat(def.eventFilter()).isNull();
        assertThat(def.dynamicCaseData()).isEmpty();
    }

    @Test
    void parsesNaiveBayesGanglionFromYaml() {
        var provider = provider("""
                                ganglia:
                                  - ganglionId: yaml-bayes
                                    type: naive-bayes
                                    handledEventTypes: [sensor.reading]
                                    outcomes: [NORMAL, ANOMALY]
                                    priors: [0.9, 0.1]
                                    features:
                                      severity:
                                        expression: ".data.severity"
                                        language: jq
                                        values: [LOW, MEDIUM, HIGH]
                                        likelihoods:
                                          - [0.7, 0.25, 0.05]
                                          - [0.1, 0.3, 0.6]
                                    signalMapping:
                                      targetOutcome: ANOMALY
                                      detectedThreshold: 0.75
                                      weakThreshold: 0.30
                                      antiThreshold: 0.05
                                """);

        var descriptors = provider.ganglionDescriptors();
        assertThat(descriptors).hasSize(1);

        var bayes = (io.casehub.ras.api.GanglionDescriptor.NaiveBayes) descriptors.getFirst();
        assertThat(bayes.ganglionId()).isEqualTo("yaml-bayes");
        assertThat(bayes.handledEventTypes()).containsExactly("sensor.reading");
        assertThat(bayes.outcomes()).containsExactly("NORMAL", "ANOMALY");
        assertThat(bayes.priors()).containsExactly(0.9, 0.1);
        assertThat(bayes.features()).containsKey("severity");

        var feature = bayes.features().get("severity");
        assertThat(feature.expression()).isInstanceOf(JQExpressionEvaluator.class);
        assertThat(feature.values()).containsExactly("LOW", "MEDIUM", "HIGH");
        assertThat(feature.likelihoods()).hasNumberOfRows(2);
        assertThat(feature.likelihoods()[0]).containsExactly(0.7, 0.25, 0.05);

        assertThat(bayes.signalMapping().targetOutcome()).isEqualTo("ANOMALY");
        assertThat(bayes.signalMapping().detectedThreshold()).isEqualTo(0.75);
        assertThat(bayes.signalMapping().antiThreshold()).isEqualTo(0.05);
    }

    @Test
    void parsesIntegerLikelihoodsAsDoubles() {
        var provider = provider("""
                                ganglia:
                                  - ganglionId: int-test
                                    type: naive-bayes
                                    handledEventTypes: [test.event]
                                    outcomes: [A, B]
                                    priors: [1, 0]
                                    features:
                                      f1:
                                        expression: ".data.f"
                                        language: jq
                                        values: [X]
                                        likelihoods:
                                          - [1]
                                          - [1]
                                    signalMapping:
                                      targetOutcome: B
                                      detectedThreshold: 0.75
                                      weakThreshold: 0.30
                                """);

        var bayes = (io.casehub.ras.api.GanglionDescriptor.NaiveBayes) provider.ganglionDescriptors().getFirst();
        assertThat(bayes.priors()[0]).isEqualTo(1.0);
        assertThat(bayes.features().get("f1").likelihoods()[0][0]).isEqualTo(1.0);
    }

    @Test
    void unknownGanglionTypeThrows() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                                                                provider("""
                                                                         ganglia:
                                                                           - ganglionId: bad
                                                                             type: unknown-type
                                                                             handledEventTypes: [test.event]
                                                                         """))
                                            .withMessageContaining("Unknown ganglion type 'unknown-type'");
    }

    @Test
    void missingGanglionIdThrows() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                                                                provider("""
                                                                         ganglia:
                                                                           - type: naive-bayes
                                                                             handledEventTypes: [test.event]
                                                                             outcomes: [A, B]
                                                                             priors: [0.5, 0.5]
                                                                             features: {}
                                                                             signalMapping:
                                                                               targetOutcome: B
                                                                               detectedThreshold: 0.75
                                                                               weakThreshold: 0.30
                                                                         """))
                                            .withMessageContaining("ganglionId");
    }

    @Test
    void noGangliaSectionReturnsEmptyDescriptors() {
        var provider = provider("""
                                situations:
                                  - situationId: sit-1
                                    eventTypes: [test.event]
                                    chainMode:
                                      type: or
                                      ganglia: [g1]
                                    triggerAction:
                                      type: notify-only
                                """);

        assertThat(provider.ganglionDescriptors()).isEmpty();
    }

    @Test
    void parsesEvidenceTemplatesOnNaiveBayes() {
        var provider = provider("""
                                ganglia:
                                  - ganglionId: with-evidence
                                    type: naive-bayes
                                    handledEventTypes: [test.event]
                                    outcomes: [A, B]
                                    priors: [0.5, 0.5]
                                    features:
                                      f1:
                                        expression: ".data.f"
                                        language: jq
                                        values: [X]
                                        likelihoods:
                                          - [1]
                                          - [1]
                                    signalMapping:
                                      targetOutcome: B
                                      detectedThreshold: 0.75
                                      weakThreshold: 0.30
                                    evidenceTemplates:
                                      raw_severity:
                                        expression: ".data.severity"
                                        language: jq
                                      sensor_id:
                                        expression: ".data.sensorId"
                                        language: mvel
                                """);

        var bayes = (io.casehub.ras.api.GanglionDescriptor.NaiveBayes) provider.ganglionDescriptors().getFirst();
        assertThat(bayes.evidenceTemplates()).hasSize(2);
        assertThat(bayes.evidenceTemplates()).containsKey("raw_severity");
        assertThat(bayes.evidenceTemplates()).containsKey("sensor_id");
        assertThat(bayes.evidenceTemplates().get("raw_severity")).isInstanceOf(io.casehub.platform.api.expression.JQExpressionEvaluator.class);
        assertThat(bayes.evidenceTemplates().get("sensor_id")).isInstanceOf(io.casehub.platform.api.expression.MvelExpressionEvaluator.class);
    }

    @Test
    void evidenceTemplatesAbsentReturnsEmptyMap() {
        var provider = provider("""
                                ganglia:
                                  - ganglionId: no-evidence
                                    type: naive-bayes
                                    handledEventTypes: [test.event]
                                    outcomes: [A, B]
                                    priors: [0.5, 0.5]
                                    features:
                                      f1:
                                        expression: ".data.f"
                                        language: jq
                                        values: [X]
                                        likelihoods:
                                          - [1]
                                          - [1]
                                    signalMapping:
                                      targetOutcome: B
                                      detectedThreshold: 0.75
                                      weakThreshold: 0.30
                                """);

        var bayes = (io.casehub.ras.api.GanglionDescriptor.NaiveBayes) provider.ganglionDescriptors().getFirst();
        assertThat(bayes.evidenceTemplates()).isEmpty();
    }

    @Test
    void parsesExpressionRulesGanglionFromYaml() {
        var provider = provider("""
                                ganglia:
                                  - ganglionId: severity-checker
                                    type: expression-rules
                                    handledEventTypes: [sensor.reading]
                                    rules:
                                      - when:
                                          expression: ".data.severity == \\"HIGH\\""
                                          language: jq
                                        signal: DETECTED
                                        confidence: 0.9
                                      - when:
                                          expression: ".data.severity == \\"MEDIUM\\""
                                          language: jq
                                        signal: WEAK
                                        confidence: 0.5
                                      - otherwise:
                                        signal: NOISE
                                        confidence: 0.0
                                    evidenceTemplates:
                                      severity:
                                        expression: ".data.severity"
                                        language: jq
                                """);

        var descriptors = provider.ganglionDescriptors();
        assertThat(descriptors).hasSize(1);
        var er = (io.casehub.ras.api.GanglionDescriptor.ExpressionRules) descriptors.getFirst();
        assertThat(er.ganglionId()).isEqualTo("severity-checker");
        assertThat(er.handledEventTypes()).containsExactly("sensor.reading");
        assertThat(er.rules()).hasSize(3);
        assertThat(er.rules().get(0).when()).isNotNull();
        assertThat(er.rules().get(0).signal()).isEqualTo(io.casehub.ras.api.DetectionSignal.DETECTED);
        assertThat(er.rules().get(0).confidence()).isEqualTo(0.9);
        assertThat(er.rules().get(2).when()).isNull();
        assertThat(er.evidenceTemplates()).containsKey("severity");
    }

    @Test
    void expressionRulesEmptyRulesThrows() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                                                                provider("""
                                                                         ganglia:
                                                                           - ganglionId: bad
                                                                             type: expression-rules
                                                                             handledEventTypes: [test.event]
                                                                             rules: []
                                                                         """))
                                            .withMessageContaining("rules must not be empty");
    }

    @Test
    void expressionRulesMissingRulesThrows() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                                                                provider("""
                                                                         ganglia:
                                                                           - ganglionId: bad
                                                                             type: expression-rules
                                                                             handledEventTypes: [test.event]
                                                                         """))
                                            .withMessageContaining("rules must not be empty");
    }

    @Test
    void expressionRulesOtherwiseNotLastThrows() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                                                                provider("""
                                                                         ganglia:
                                                                           - ganglionId: bad
                                                                             type: expression-rules
                                                                             handledEventTypes: [test.event]
                                                                             rules:
                                                                               - otherwise:
                                                                                 signal: NOISE
                                                                                 confidence: 0.0
                                                                               - when:
                                                                                   expression: ".data.x"
                                                                                   language: jq
                                                                                 signal: DETECTED
                                                                                 confidence: 0.9
                                                                         """))
                                            .withMessageContaining("otherwise");
    }

    @Test
    void expressionRulesInvalidSignalThrows() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                                                                provider("""
                                                                         ganglia:
                                                                           - ganglionId: bad
                                                                             type: expression-rules
                                                                             handledEventTypes: [test.event]
                                                                             rules:
                                                                               - when:
                                                                                   expression: ".data.x"
                                                                                   language: jq
                                                                                 signal: INVALID
                                                                                 confidence: 0.5
                                                                         """))
                                            .withMessageContaining("Invalid signal");
    }

    @Test
    void expressionRulesConfidenceOutOfRangeThrows() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                                                                provider("""
                                                                         ganglia:
                                                                           - ganglionId: bad
                                                                             type: expression-rules
                                                                             handledEventTypes: [test.event]
                                                                             rules:
                                                                               - when:
                                                                                   expression: ".data.x"
                                                                                   language: jq
                                                                                 signal: DETECTED
                                                                                 confidence: 1.5
                                                                         """))
                                            .withMessageContaining("confidence must be 0.0-1.0");
    }

    @Test
    void expressionRulesBothWhenAndOtherwiseThrows() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                                                                provider("""
                                                                         ganglia:
                                                                           - ganglionId: bad
                                                                             type: expression-rules
                                                                             handledEventTypes: [test.event]
                                                                             rules:
                                                                               - when:
                                                                                   expression: ".data.x"
                                                                                   language: jq
                                                                                 otherwise:
                                                                                 signal: DETECTED
                                                                                 confidence: 0.5
                                                                         """))
                                            .withMessageContaining("both 'when' and 'otherwise'");
    }

    @Test
    void expressionRulesNeitherWhenNorOtherwiseThrows() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                                                                provider("""
                                                                         ganglia:
                                                                           - ganglionId: bad
                                                                             type: expression-rules
                                                                             handledEventTypes: [test.event]
                                                                             rules:
                                                                               - signal: DETECTED
                                                                                 confidence: 0.5
                                                                         """))
                                            .withMessageContaining("neither 'when' nor 'otherwise'");
    }

    @Test
    void expressionRulesHandledEventTypesEmptyThrows() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                                                                provider("""
                                                                         ganglia:
                                                                           - ganglionId: bad
                                                                             type: expression-rules
                                                                             handledEventTypes: []
                                                                             rules:
                                                                               - when:
                                                                                   expression: ".data.x"
                                                                                   language: jq
                                                                                 signal: DETECTED
                                                                                 confidence: 0.5
                                                                         """))
                                            .withMessageContaining("handledEventTypes must not be empty");
    }

    @Test
    void expressionRulesMissingSignalThrows() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                                                                provider("""
                                                                         ganglia:
                                                                           - ganglionId: bad
                                                                             type: expression-rules
                                                                             handledEventTypes: [test.event]
                                                                             rules:
                                                                               - when:
                                                                                   expression: ".data.x"
                                                                                   language: jq
                                                                                 confidence: 0.5
                                                                         """))
                                            .withMessageContaining("signal");
    }

    @Test
    void expressionRulesMissingConfidenceThrows() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                                                                provider("""
                                                                         ganglia:
                                                                           - ganglionId: bad
                                                                             type: expression-rules
                                                                             handledEventTypes: [test.event]
                                                                             rules:
                                                                               - when:
                                                                                   expression: ".data.x"
                                                                                   language: jq
                                                                                 signal: DETECTED
                                                                         """))
                                            .withMessageContaining("confidence");
    }

    @Test
    void expressionRulesNegativeConfidenceThrows() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                                                                provider("""
                                                                         ganglia:
                                                                           - ganglionId: bad
                                                                             type: expression-rules
                                                                             handledEventTypes: [test.event]
                                                                             rules:
                                                                               - when:
                                                                                   expression: ".data.x"
                                                                                   language: jq
                                                                                 signal: DETECTED
                                                                                 confidence: -0.1
                                                                         """))
                                            .withMessageContaining("confidence must be 0.0-1.0");
    }


    @Test
    void endToEndYamlNaiveBayesGanglionDetectsAndTriggers() {
        var provider = new YamlSituationDefinitionProvider(
                Thread.currentThread().getContextClassLoader()
                      .getResourceAsStream("META-INF/ras-situations-e2e-naivebayes.yaml"));

        assertThat(provider.ganglionDescriptors()).hasSize(1);
        assertThat(provider.registrations()).hasSize(1);

        var jqEngine = new io.casehub.platform.expression.JQExpressionEngine();
        var engines = new io.casehub.platform.expression.DefaultExpressionEngineRegistry();
        engines.register(jqEngine);
        var stateStore = new InMemoryGanglionStateStore();

        var registry = new SituationDefinitionRegistry(
                java.util.List.of(provider), java.util.List.of(), engines, stateStore, null);

        assertThat(registry.ganglion("e2e-bayes")).isNotNull();
        assertThat(registry.ganglion("e2e-bayes")).isInstanceOf(NaiveBayesGanglion.class);

        assertThat(registry.findByEventType("test.e2e")).hasSize(1);
        assertThat(registry.findByEventType("test.e2e").getFirst()
                           .definition().situationId()).isEqualTo("e2e-situation");

        var event = io.cloudevents.core.builder.CloudEventBuilder.v1()
                                                                 .withId("e2e-1")
                                                                 .withSource(java.net.URI.create("/test"))
                                                                 .withType("test.e2e")
                                                                 .withSubject("device-1")
                                                                 .withData("application/json", "{\"severity\":\"HIGH\"}".getBytes())
                                                                 .build();

        var ganglion = (NaiveBayesGanglion) registry.ganglion("e2e-bayes");
        var ctx = io.casehub.ras.api.SituationContext.initial("e2e-situation", "device-1", "tenant-1",
                                                              java.time.Instant.parse("2026-07-20T10:00:00Z"));

        io.casehub.ras.api.DetectionResult result = ganglion.detect(event, ctx).await().indefinitely();

        assertThat(result.ganglionId()).isEqualTo("e2e-bayes");
        double posterior = (double) result.evidence().get("posterior");
        assertThat(posterior).isGreaterThan(0.5);
        assertThat(result.signal().isAtLeast(io.casehub.ras.api.DetectionSignal.WEAK)).isTrue();

        @SuppressWarnings("unchecked")
        java.util.Map<String, String> features = (java.util.Map<String, String>) result.evidence().get("features");
        assertThat(features).containsEntry("severity", "HIGH");
    }

    @Test
    void parsesPerRuleEvidenceTemplates() {
        var provider = provider("""
                                ganglia:
                                  - ganglionId: rule-evid
                                    type: expression-rules
                                    handledEventTypes: [test.event]
                                    rules:
                                      - when:
                                          expression: ".data.x == \\"HIGH\\""
                                          language: jq
                                        signal: DETECTED
                                        confidence: 0.9
                                        evidenceTemplates:
                                          reason:
                                            expression: ".data.reason"
                                            language: jq
                                      - otherwise:
                                        signal: NOISE
                                        confidence: 0.0
                                """);
        var er = (io.casehub.ras.api.GanglionDescriptor.ExpressionRules) provider.ganglionDescriptors().getFirst();
        assertThat(er.rules().get(0).evidenceTemplates()).containsKey("reason");
        assertThat(er.rules().get(1).evidenceTemplates()).isEmpty();
    }

    @Test
    void perRuleEvidenceTemplatesAbsentDefaultsToEmpty() {
        var provider = provider("""
                                ganglia:
                                  - ganglionId: no-evid
                                    type: expression-rules
                                    handledEventTypes: [test.event]
                                    rules:
                                      - when:
                                          expression: ".data.x"
                                          language: jq
                                        signal: DETECTED
                                        confidence: 0.9
                                """);
        var er = (io.casehub.ras.api.GanglionDescriptor.ExpressionRules) provider.ganglionDescriptors().getFirst();
        assertThat(er.rules().get(0).evidenceTemplates()).isEmpty();
    }

    @Test
    void parsesOutcomeEvidenceTemplates() {
        var provider = provider("""
                                ganglia:
                                  - ganglionId: outcome-evid
                                    type: naive-bayes
                                    handledEventTypes: [test.event]
                                    outcomes: [A, B]
                                    priors: [0.5, 0.5]
                                    features:
                                      f1:
                                        expression: ".data.f"
                                        language: jq
                                        values: [X]
                                        likelihoods:
                                          - [1]
                                          - [1]
                                    signalMapping:
                                      targetOutcome: B
                                      detectedThreshold: 0.75
                                      weakThreshold: 0.30
                                    outcomeEvidenceTemplates:
                                      A:
                                        reason:
                                          expression: ".data.reason"
                                          language: jq
                                      B:
                                        detail:
                                          expression: ".data.detail"
                                          language: jq
                                """);
        var nb = (io.casehub.ras.api.GanglionDescriptor.NaiveBayes) provider.ganglionDescriptors().getFirst();
        assertThat(nb.outcomeEvidenceTemplates()).hasSize(2);
        assertThat(nb.outcomeEvidenceTemplates().get("A")).containsKey("reason");
        assertThat(nb.outcomeEvidenceTemplates().get("B")).containsKey("detail");
    }

    @Test
    void outcomeEvidenceTemplatesAbsentDefaultsToEmpty() {
        var provider = provider("""
                                ganglia:
                                  - ganglionId: no-oet
                                    type: naive-bayes
                                    handledEventTypes: [test.event]
                                    outcomes: [A, B]
                                    priors: [0.5, 0.5]
                                    features:
                                      f1:
                                        expression: ".data.f"
                                        language: jq
                                        values: [X]
                                        likelihoods:
                                          - [1]
                                          - [1]
                                    signalMapping:
                                      targetOutcome: B
                                      detectedThreshold: 0.75
                                      weakThreshold: 0.30
                                """);
        var nb = (io.casehub.ras.api.GanglionDescriptor.NaiveBayes) provider.ganglionDescriptors().getFirst();
        assertThat(nb.outcomeEvidenceTemplates()).isEmpty();
    }

    @Test
    void outcomeEvidenceTemplatesUnknownOutcomeThrows() {
        assertThatIllegalArgumentException().isThrownBy(() -> provider("""
                                                                       ganglia:
                                                                         - ganglionId: bad
                                                                           type: naive-bayes
                                                                           handledEventTypes: [test.event]
                                                                           outcomes: [A, B]
                                                                           priors: [0.5, 0.5]
                                                                           features:
                                                                             f1:
                                                                               expression: ".data.f"
                                                                               language: jq
                                                                               values: [X]
                                                                               likelihoods:
                                                                                 - [1]
                                                                                 - [1]
                                                                           signalMapping:
                                                                             targetOutcome: B
                                                                             detectedThreshold: 0.75
                                                                             weakThreshold: 0.30
                                                                           outcomeEvidenceTemplates:
                                                                             UNKNOWN:
                                                                               reason:
                                                                                 expression: ".data.x"
                                                                                 language: jq
                                                                       """))
                                            .withMessageContaining("UNKNOWN")
                                            .withMessageContaining("not in outcomes");
    }

    @Test
    void endToEndPerRuleEvidenceTemplates() {
        var provider = new YamlSituationDefinitionProvider(
                Thread.currentThread().getContextClassLoader()
                      .getResourceAsStream("META-INF/ras-situations-e2e-per-rule-evidence.yaml"));

        var engines = new io.casehub.platform.expression.DefaultExpressionEngineRegistry();
        engines.register(new io.casehub.platform.expression.JQExpressionEngine());
        var registry = new SituationDefinitionRegistry(
                java.util.List.of(provider), java.util.List.of(), engines, new InMemoryGanglionStateStore(), null);

        var event = io.cloudevents.core.builder.CloudEventBuilder.v1()
                                                                 .withId("e2e-1").withSource(java.net.URI.create("/test")).withType("test.e2e")
                                                                 .withSubject("device-1")
                                                                 .withData("application/json", "{\"severity\":\"HIGH\",\"reason\":\"temperature spike\"}".getBytes())
                                                                 .build();

        var ganglion = registry.ganglion("e2e-rules");
        var ctx = io.casehub.ras.api.SituationContext.initial("e2e-per-rule", "device-1", "tenant-1",
                                                              java.time.Instant.parse("2026-07-22T10:00:00Z"));
        var result = ganglion.detect(event, ctx).await().indefinitely();

        assertThat(result.evidence()).containsEntry("matchedRuleIndex", 0);
        assertThat(result.evidence()).containsEntry("reason", "temperature spike");
        assertThat(result.evidence()).containsEntry("severity", "HIGH");
    }

    @Test
    void endToEndPerOutcomeEvidenceTemplates() {
        var provider = new YamlSituationDefinitionProvider(
                Thread.currentThread().getContextClassLoader()
                      .getResourceAsStream("META-INF/ras-situations-e2e-per-outcome-evidence.yaml"));

        var engines = new io.casehub.platform.expression.DefaultExpressionEngineRegistry();
        engines.register(new io.casehub.platform.expression.JQExpressionEngine());
        var registry = new SituationDefinitionRegistry(
                java.util.List.of(provider), java.util.List.of(), engines, new InMemoryGanglionStateStore(), null);

        var event = io.cloudevents.core.builder.CloudEventBuilder.v1()
                                                                 .withId("e2e-1").withSource(java.net.URI.create("/test")).withType("test.e2e")
                                                                 .withSubject("sensor-1")
                                                                 .withData("application/json",
                                                                           "{\"severity\":\"HIGH\",\"anomalyType\":\"overheating\",\"sensorId\":\"S42\"}".getBytes())
                                                                 .build();

        var ganglion = registry.ganglion("e2e-bayes-outcome");
        var ctx = io.casehub.ras.api.SituationContext.initial("e2e-per-outcome", "sensor-1", "tenant-1",
                                                              java.time.Instant.parse("2026-07-22T10:00:00Z"));
        var result = ganglion.detect(event, ctx).await().indefinitely();

        assertThat(result.evidence()).containsKey("posterior");
        assertThat(result.evidence()).containsKey("winningOutcome");
        assertThat(result.evidence()).containsEntry("sensor_id", "S42");
    }
}
