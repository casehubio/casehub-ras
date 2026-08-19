package io.casehub.ras.runtime;

import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.CaseTriggerConfig;
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

class DrainAllBuffersTest {

    private static final CaseTriggerConfig TRIGGER_CONFIG =
            new CaseTriggerConfig("ns", "case", "1.0", Map.of());

    private CloudEvent event(String type, Instant time) {
        return CloudEventBuilder.v1()
                .withId("evt-" + time.toEpochMilli())
                .withSource(URI.create("/test"))
                .withType(type)
                .withTime(OffsetDateTime.ofInstant(time, ZoneOffset.UTC))
                .withExtension("tenancyid", "tenant-a")
                .build();
    }

    @Test
    void drainAllBuffersFlushesRemainingEvents() {
        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), Duration.ofSeconds(5),
                new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        var metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(new SimpleMeterRegistry());
        metrics.init();
        var changeEvent = new NoOpEvent();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry, 3, changeEvent, metrics);

        Instant t1 = Instant.parse("2026-06-25T10:00:00Z");
        evaluator.evaluate(event("temp.reading", t1), def, "key-1", "tenant-a");

        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(evaluator.activeBufferCount()).isEqualTo(1);

        evaluator.drainAllBuffers();

        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(evaluator.activeBufferCount()).isZero();
    }

    @Test
    void drainAllBuffersIsNoOpWhenEmpty() {
        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null,
                new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(ganglion));
        var metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(new SimpleMeterRegistry());
        metrics.init();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry, 3, new NoOpEvent(), metrics);

        evaluator.drainAllBuffers();

        assertThat(caseTrigger.firedCases()).isEmpty();
    }

    private static class NoOpEvent implements Event<SituationChangeEvent> {
        @Override public void fire(SituationChangeEvent event) {}
        @Override public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event) { return CompletableFuture.completedFuture(event); }
        @Override public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event, NotificationOptions options) { return CompletableFuture.completedFuture(event); }
        @Override public Event<SituationChangeEvent> select(Annotation... qualifiers) { return this; }
        @Override public <U extends SituationChangeEvent> Event<U> select(Class<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public <U extends SituationChangeEvent> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
    }
}
