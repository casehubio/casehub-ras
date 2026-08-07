package io.casehub.ras.runtime;

import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.FeedbackConfig;
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
import io.casehub.ras.api.SituationChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class SituationEvaluatorFeedbackTest {

    private static final Instant T1 = Instant.parse("2026-08-06T10:00:00Z");
    private static final CaseTriggerConfig TRIGGER_CONFIG =
            new CaseTriggerConfig("ns", "case", "1.0", Map.of());

    private InMemorySituationStore store;
    private MockCaseTrigger caseTrigger;
    private DefaultRasTriggerPolicy policy;
    private SimpleMeterRegistry meterRegistry;
    private RasMetrics metrics;
    private NoOpEvent changeEvent;

    @BeforeEach
    void setUp() {
        store = new InMemorySituationStore();
        caseTrigger = new MockCaseTrigger();
        policy = new DefaultRasTriggerPolicy();
        meterRegistry = new SimpleMeterRegistry();
        changeEvent = new NoOpEvent();
    }

    private void initMetrics(SituationDefinitionRegistry registry) {
        metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(meterRegistry);
        metrics.init();
    }

    private CloudEvent event(String type) {
        return CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/test"))
                .withType(type)
                .withSubject("key-1")
                .withTime(OffsetDateTime.ofInstant(T1, ZoneOffset.UTC))
                .build();
    }

    private FeedbackConfig feedbackConfig() {
        return new FeedbackConfig(
                Set.of("dismissed"), Set.of("escalated"),
                Duration.ofHours(6), 0.1, Duration.ofDays(90), false);
    }

    @Test
    void suppressedEventSkipsDetection() {
        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(TRIGGER_CONFIG), null,
                null, null, Map.of(), feedbackConfig());

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        initMetrics(registry);

        var ledger = new InMemoryOutcomeLedger();
        ledger.record(new io.casehub.ras.api.OutcomeRecord(
                "sit-1", "key-1", "tenant-a", "dismissed",
                io.casehub.ras.api.OutcomeClassification.NOISE,
                Instant.now().minus(Duration.ofMinutes(30)),
                java.util.UUID.randomUUID()));

        var suppression = new DefaultSuppressionStrategy();
        var evaluator = new SituationEvaluator(store, policy, caseTrigger, registry,
                3, changeEvent, metrics, suppression, ledger, null);

        evaluator.evaluate(event("test.event"), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(store.find("sit-1", "key-1", "tenant-a")).isEmpty();
        assertThat(meterRegistry.counter("ras.feedback.suppressions_total",
                "situation_id", "sit-1", "tenancy_id", "tenant-a").count()).isEqualTo(1.0);
    }

    @Test
    void eventNotSuppressedWhenOutsideCooldown() {
        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(TRIGGER_CONFIG), null,
                null, null, Map.of(), feedbackConfig());

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        initMetrics(registry);

        var ledger = new InMemoryOutcomeLedger();
        ledger.record(new io.casehub.ras.api.OutcomeRecord(
                "sit-1", "key-1", "tenant-a", "dismissed",
                io.casehub.ras.api.OutcomeClassification.NOISE,
                Instant.now().minus(Duration.ofHours(12)),
                java.util.UUID.randomUUID()));

        var suppression = new DefaultSuppressionStrategy();
        var evaluator = new SituationEvaluator(store, policy, caseTrigger, registry,
                3, changeEvent, metrics, suppression, ledger, null);

        evaluator.evaluate(event("test.event"), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void thresholdOverrideApplied() {
        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                FixedDetectionResult.detected("g1", 0.6));
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null,
                new ChainMode.Threshold(Set.of("g1"), 0.5),
                new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        initMetrics(registry);

        var feedbackState = new FeedbackState();
        feedbackState.applyThresholdOverride("sit-1", "tenant-a", 0.8);

        var evaluator = new SituationEvaluator(store, policy, caseTrigger, registry,
                3, changeEvent, metrics, null, null, feedbackState);

        evaluator.evaluate(event("test.event"), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).isEmpty();
    }

    @Test
    void thresholdOverrideNotAppliedToNonThresholdChainMode() {
        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                FixedDetectionResult.detected("g1", 0.6));
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        initMetrics(registry);

        var feedbackState = new FeedbackState();
        feedbackState.applyThresholdOverride("sit-1", "tenant-a", 0.99);

        var evaluator = new SituationEvaluator(store, policy, caseTrigger, registry,
                3, changeEvent, metrics, null, null, feedbackState);

        evaluator.evaluate(event("test.event"), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void noFeedbackWhenDependenciesAbsent() {
        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(TRIGGER_CONFIG), null,
                null, null, Map.of(), feedbackConfig());

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        initMetrics(registry);

        var evaluator = new SituationEvaluator(store, policy, caseTrigger, registry,
                3, changeEvent, metrics);

        evaluator.evaluate(event("test.event"), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    @Test
    void noSuppressionWhenFeedbackConfigNull() {
        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        initMetrics(registry);

        var ledger = new InMemoryOutcomeLedger();
        ledger.record(new io.casehub.ras.api.OutcomeRecord(
                "sit-1", "key-1", "tenant-a", "dismissed",
                io.casehub.ras.api.OutcomeClassification.NOISE,
                Instant.now().minus(Duration.ofMinutes(1)),
                java.util.UUID.randomUUID()));

        var suppression = new DefaultSuppressionStrategy();
        var evaluator = new SituationEvaluator(store, policy, caseTrigger, registry,
                3, changeEvent, metrics, suppression, ledger, null);

        evaluator.evaluate(event("test.event"), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).hasSize(1);
    }

    private static class NoOpEvent implements Event<SituationChangeEvent> {
        @Override public void fire(SituationChangeEvent event) {}
        @Override public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event) { return null; }
        @Override public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event, NotificationOptions options) { return null; }
        @Override public Event<SituationChangeEvent> select(Annotation... qualifiers) { return this; }
        @Override public <U extends SituationChangeEvent> Event<U> select(Class<U> subtype, Annotation... qualifiers) { return null; }
        @Override public <U extends SituationChangeEvent> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { return null; }
    }
}
