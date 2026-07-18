package io.casehub.ras.runtime;

import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.CorrelationKeyExtractor;
import io.casehub.ras.api.DefaultCorrelationKeyExtractor;
import io.casehub.ras.api.EventFilter;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.TriggerAction;
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

import java.lang.annotation.Annotation;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class RasEngineTest {

    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private static final Instant T1 = Instant.parse("2026-06-25T10:00:00Z");
    private static final CaseTriggerConfig TRIGGER =
            new CaseTriggerConfig("ns", "case", "1.0", Map.of());

    private CloudEvent event(String type, String tenancyId) {
        var builder = CloudEventBuilder.v1()
                .withId("evt-1").withSource(URI.create("/test")).withType(type)
                .withTime(OffsetDateTime.ofInstant(T1, ZoneOffset.UTC));
        if (tenancyId != null) {
            builder = builder.withExtension("tenancyid", tenancyId);
        }
        return builder.build();
    }

    @Test
    void routesEventToMatchingDefinition() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER), null);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(meterRegistry);
        metrics.init();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry, 3, new NoOpChangeEvent(), metrics);
        var engine = new RasEngine(registry, evaluator, metrics);

        engine.onCloudEvent(event("temp.reading", "tenant-a"));

        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(meterRegistry.counter("ras.engine.events.received",
                "event_type", "temp.reading").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("ras.engine.events.routed",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count()).isEqualTo(1.0);
    }

    @Test
    void skipsEventWithoutTenancyId() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER), null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(meterRegistry);
        metrics.init();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry, 3, new NoOpChangeEvent(), metrics);
        var engine = new RasEngine(registry, evaluator, metrics);

        engine.onCloudEvent(event("temp.reading", null));

        assertThat(ganglion.callCount()).isEqualTo(0);
        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(meterRegistry.counter("ras.engine.events.received",
                "event_type", "temp.reading").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("ras.engine.events.skipped",
                "reason", "no_tenancy_id").count()).isEqualTo(1.0);
    }

    @Test
    void unmatchedEventTypeIsIgnored() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")), new TriggerAction.CreateCase(TRIGGER), null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(meterRegistry);
        metrics.init();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry, 3, new NoOpChangeEvent(), metrics);
        var engine = new RasEngine(registry, evaluator, metrics);

        engine.onCloudEvent(event("unknown.type", "tenant-a"));

        assertThat(ganglion.callCount()).isEqualTo(0);
        assertThat(meterRegistry.counter("ras.engine.events.received",
                "event_type", "unknown.type").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("ras.engine.events.skipped",
                "reason", "no_matching_situation").count()).isEqualTo(1.0);
    }


    @Test
    void filteredEventSkipsEvaluation() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER), null);
        EventFilter rejectAll = event -> false;
        var reg = new SituationRegistration(def, DefaultCorrelationKeyExtractor.INSTANCE,
                                            rejectAll, null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        var store       = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var metrics     = new RasMetrics(registry);
        metrics.setMeterRegistry(meterRegistry);
        metrics.init();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                                               caseTrigger, registry, 3, new NoOpChangeEvent(), metrics);
        var engine = new RasEngine(registry, evaluator, metrics);

        engine.onCloudEvent(event("temp.reading", "t1"));

        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(meterRegistry.counter("ras.events.filtered",
                                         "situation_id", "sit-1", "tenancy_id", "t1").count()).isEqualTo(1.0);
    }

    @Test
    void nullFilterAllowsEvent() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER), null);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        var store       = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var metrics     = new RasMetrics(registry);
        metrics.setMeterRegistry(meterRegistry);
        metrics.init();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                                               caseTrigger, registry, 3, new NoOpChangeEvent(), metrics);
        var engine = new RasEngine(registry, evaluator, metrics);

        engine.onCloudEvent(event("temp.reading", "t1"));

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void filterExceptionTreatedAsPassThrough() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER), null);
        EventFilter brokenFilter = event -> {throw new RuntimeException("broken");};
        var reg = new SituationRegistration(def, DefaultCorrelationKeyExtractor.INSTANCE,
                                            brokenFilter, null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        var store       = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var metrics     = new RasMetrics(registry);
        metrics.setMeterRegistry(meterRegistry);
        metrics.init();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                                               caseTrigger, registry, 3, new NoOpChangeEvent(), metrics);
        var engine = new RasEngine(registry, evaluator, metrics);

        engine.onCloudEvent(event("temp.reading", "t1"));

        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(meterRegistry.counter("ras.expression.error",
                                         "situation_id", "sit-1", "expression_point", "event_filter").count()).isEqualTo(1.0);
    }

    @Test
    void correlationKeyExpressionErrorFallsBackToDefault() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER), null);
        CorrelationKeyExtractor brokenExtractor = event -> {throw new RuntimeException("broken");};
        var                     reg             = new SituationRegistration(def, brokenExtractor, null, null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        var store       = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var metrics     = new RasMetrics(registry);
        metrics.setMeterRegistry(meterRegistry);
        metrics.init();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                                               caseTrigger, registry, 3, new NoOpChangeEvent(), metrics);
        var engine = new RasEngine(registry, evaluator, metrics);

        engine.onCloudEvent(event("temp.reading", "t1"));

        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(meterRegistry.counter("ras.expression.error",
                                         "situation_id", "sit-1", "expression_point", "correlation_key").count()).isEqualTo(1.0);
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
