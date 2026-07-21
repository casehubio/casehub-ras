package io.casehub.ras.runtime;

import io.casehub.platform.expression.DefaultExpressionEngineRegistry;
import io.casehub.platform.expression.JQExpressionEngine;
import io.casehub.platform.expression.MvelExpressionEngine;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.persistence.memory.InMemorySituationStore;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockCaseTrigger;
import io.casehub.ras.testing.MockGanglion;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionIntegrationTest {

    @Test
    void endToEnd_yamlExpressions_filterCorrelateAndDynamicData() {
        String yaml = """
                situations:
                  - situationId: sla-breach
                    eventTypes: [order.status]
                    correlationWindow: PT30M
                    correlationKey:
                      expression: ".data.orderId"
                      language: jq
                    eventFilter:
                      expression: ".data.severity >= 3"
                      language: jq
                    dynamicCaseData:
                      extractedOrder:
                        expression: ".correlationKey"
                        language: jq
                    chainMode:
                      type: or
                      ganglia: [sla-detector]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: case
                      caseVersion: "1.0"
                      baseCaseData:
                        static: value
                """;
        var provider = new YamlSituationDefinitionProvider(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        var ganglion = new MockGanglion("sla-detector", Set.of("order.status"),
                FixedDetectionResult.detected("sla-detector", 0.95));
        var exprRegistry = buildExpressionRegistry();
        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(ganglion), exprRegistry);

        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(new SimpleMeterRegistry());
        metrics.init();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry, 3, new NoOpChangeEvent(), metrics);
        var engine = new RasEngine(registry, evaluator, metrics);

        CloudEvent passingEvent = CloudEventBuilder.v1()
                .withId("evt-1").withSource(URI.create("/test")).withType("order.status")
                .withSubject("order-ABC")
                .withTime(OffsetDateTime.now(ZoneOffset.UTC))
                .withExtension("tenancyid", "tenant-A")
                .withData("application/json",
                        "{\"orderId\":\"ORD-999\",\"severity\":5}".getBytes())
                .build();
        engine.onCloudEvent(passingEvent);

        assertThat(caseTrigger.firedCases()).hasSize(1);

        CloudEvent filteredEvent = CloudEventBuilder.v1()
                .withId("evt-2").withSource(URI.create("/test")).withType("order.status")
                .withSubject("order-DEF")
                .withTime(OffsetDateTime.now(ZoneOffset.UTC))
                .withExtension("tenancyid", "tenant-A")
                .withData("application/json",
                        "{\"orderId\":\"ORD-000\",\"severity\":1}".getBytes())
                .build();
        engine.onCloudEvent(filteredEvent);

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void jqCorrelationKeyExtraction() {
        String yaml = """
                situations:
                  - situationId: test-sit
                    eventTypes: [test.event]
                    correlationWindow: PT10M
                    correlationKey:
                      expression: ".data.customId"
                      language: jq
                    chainMode:
                      type: or
                      ganglia: [g1]
                    triggerAction:
                      type: create-case
                      caseNamespace: ns
                      caseName: case
                      caseVersion: "1.0"
                """;
        var provider = new YamlSituationDefinitionProvider(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                FixedDetectionResult.detected("g1", 0.9));
        var exprRegistry = buildExpressionRegistry();
        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(ganglion), exprRegistry);

        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(new SimpleMeterRegistry());
        metrics.init();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry, 3, new NoOpChangeEvent(), metrics);
        var engine = new RasEngine(registry, evaluator, metrics);

        CloudEvent event = CloudEventBuilder.v1()
                .withId("evt-1").withSource(URI.create("/test")).withType("test.event")
                .withSubject("ignored-subject")
                .withTime(OffsetDateTime.now(ZoneOffset.UTC))
                .withExtension("tenancyid", "tenant-A")
                .withData("application/json",
                        "{\"customId\":\"CUSTOM-42\"}".getBytes())
                .build();
        engine.onCloudEvent(event);

        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(caseTrigger.firedCases().get(0).context().correlationKey())
                .isEqualTo("CUSTOM-42");
    }

    @Test
    void endToEnd_expressionRulesGanglion_withEvidenceTemplates() {
        String yaml = """
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
                            source:
                              expression: ".data.source"
                              language: jq
                      situations:
                        - situationId: severity-alert
                          eventTypes: [sensor.reading]
                          correlationWindow: PT1H
                          chainMode:
                            type: or
                            ganglia: [severity-checker]
                          triggerAction:
                            type: create-case
                            caseNamespace: ops
                            caseName: alert
                            caseVersion: "1"
                      """;
        var provider = new YamlSituationDefinitionProvider(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        var exprRegistry = buildExpressionRegistry();
        var registry = new SituationDefinitionRegistry(
                List.of(provider), List.of(), exprRegistry,
                new InMemoryGanglionStateStore(), new SimpleMeterRegistry());

        var store       = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var metrics     = new RasMetrics(registry);
        metrics.setMeterRegistry(new SimpleMeterRegistry());
        metrics.init();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                                               caseTrigger, registry, 3, new NoOpChangeEvent(), metrics);
        var engine = new RasEngine(registry, evaluator, metrics);

        CloudEvent highEvent = CloudEventBuilder.v1()
                                                .withId("evt-1").withSource(URI.create("/sensor"))
                                                .withType("sensor.reading")
                                                .withSubject("sensor-42")
                                                .withTime(OffsetDateTime.now(ZoneOffset.UTC))
                                                .withExtension("tenancyid", "tenant-A")
                                                .withData("application/json",
                                                          "{\"severity\":\"HIGH\",\"source\":\"factory-1\"}".getBytes())
                                                .build();
        engine.onCloudEvent(highEvent);

        assertThat(caseTrigger.firedCases()).hasSize(1);
        var firedContext = caseTrigger.firedCases().get(0).context();
        assertThat(firedContext.detections()).hasSize(1);

        var detection = firedContext.detections().get(0).result();
        assertThat(detection.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(detection.confidence()).isEqualTo(0.9);
        assertThat(detection.evidence()).containsEntry("matchedRuleIndex", 0);
        assertThat(detection.evidence()).containsEntry("severity", "HIGH");
        assertThat(detection.evidence()).containsEntry("source", "factory-1");
    }


    private static DefaultExpressionEngineRegistry buildExpressionRegistry() {
        var registry = new DefaultExpressionEngineRegistry();
        registry.register(new JQExpressionEngine());
        registry.register(new MvelExpressionEngine());
        return registry;
    }

    private static class NoOpChangeEvent implements Event<SituationChangeEvent> {
        @Override public void fire(SituationChangeEvent event) {}
        @Override public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event) { return CompletableFuture.completedFuture(event); }
        @Override public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event, NotificationOptions options) { return CompletableFuture.completedFuture(event); }
        @Override public Event<SituationChangeEvent> select(Annotation... qualifiers) { return this; }
        @Override public <U extends SituationChangeEvent> Event<U> select(Class<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public <U extends SituationChangeEvent> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
    }
}
