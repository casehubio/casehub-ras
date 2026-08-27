package io.casehub.ras.runtime;

import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.DefaultCorrelationKeyExtractor;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.GanglionDescriptor;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationDefinitionProvider;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class MetaSituationIntegrationTest {

    private static final CaseTriggerConfig TRIGGER_CONFIG =
            new CaseTriggerConfig("ns", "case", "1.0", Map.of());

    @Test
    void child_triggers_bridge_evaluates_meta_situation() {
        var childGanglion = new MockGanglion("child-g", Set.of("io.casehub.service.error"),
                FixedDetectionResult.detected("child-g", 0.9));
        var childDef = new SituationDefinition("service-health", Set.of("io.casehub.service.error"),
                null, null, new ChainMode.Or(Set.of("child-g")),
                new TriggerAction.NotifyOnly(), null);

        var watcherMapping = Map.of(
                SituationChangeEvent.ChangeType.TRIGGERED, DetectionSignal.DETECTED);
        var metaDef = new SituationDefinition("system-degradation",
                Set.of("ras.situation.triggered"),
                null, null, new ChainMode.Count("sw-1", 3),
                new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        SituationDefinitionProvider provider = new SituationDefinitionProvider() {
            @Override public List<SituationRegistration> registrations() {
                return List.of(new SituationRegistration(childDef), new SituationRegistration(metaDef));
            }
            @Override public List<GanglionDescriptor> ganglionDescriptors() {
                return List.of(new GanglionDescriptor.SituationWatcher("sw-1", watcherMapping, Map.of()));
            }
        };

        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var registry = new SituationDefinitionRegistry(List.of(provider), List.of(childGanglion));
        var metrics = createMetrics(registry);
        var bridgingEvent = new BridgingChangeEvent();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry, 3, bridgingEvent, metrics);
        bridgingEvent.init(evaluator, registry);

        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 3; i++) {
            CloudEvent childEvent = CloudEventBuilder.v1()
                    .withId("evt-" + i)
                    .withSource(URI.create("/test"))
                    .withType("io.casehub.service.error")
                    .withSubject("server-" + i)
                    .withTime(OffsetDateTime.ofInstant(t.plusSeconds(i * 60), ZoneOffset.UTC))
                    .withExtension("tenancyid", "tenant-a")
                    .build();
            evaluator.evaluate(childEvent, childDef, "server-" + i, "tenant-a");
        }

        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(bridgingEvent.allFired()).anyMatch(
                e -> e.changeType() == SituationChangeEvent.ChangeType.TRIGGERED
                     && e.situationId().equals("system-degradation"));
    }

    @Test
    void deadline_forces_trigger_on_pre_existing_context() {
        var watcherMapping = Map.of(
                SituationChangeEvent.ChangeType.TRIGGERED, DetectionSignal.DETECTED);
        var metaDef = new SituationDefinition("system-degradation",
                Set.of("ras.situation.triggered"),
                null, null, new ChainMode.Count("sw-1", 5),
                new TriggerAction.CreateCase(TRIGGER_CONFIG),
                null, null, null, Map.of(), null, Duration.ofMinutes(5));

        SituationDefinitionProvider provider = new SituationDefinitionProvider() {
            @Override public List<SituationRegistration> registrations() {
                return List.of(new SituationRegistration(metaDef));
            }
            @Override public List<GanglionDescriptor> ganglionDescriptors() {
                return List.of(new GanglionDescriptor.SituationWatcher("sw-1", watcherMapping, Map.of()));
            }
        };

        var store = new InMemorySituationStore();
        var caseTrigger = new MockCaseTrigger();
        var registry = new SituationDefinitionRegistry(List.of(provider), List.of());
        var metrics = createMetrics(registry);
        var bridgingEvent = new BridgingChangeEvent();
        var evaluator = new SituationEvaluator(store, new DefaultRasTriggerPolicy(),
                caseTrigger, registry, 3, bridgingEvent, metrics);

        store.save(SituationContext.initial("system-degradation", "child-sit", "tenant-a",
                Instant.now().minus(Duration.ofMinutes(10))));

        assertThat(caseTrigger.firedCases()).as("before deadline").isEmpty();

        var job = new DeadlineCheckJob(store, registry, evaluator, metrics);
        job.check();

        assertThat(caseTrigger.firedCases()).as("after deadline").hasSize(1);
    }

    @Test
    void nesting_rejected_by_conservative_cycle_detection() {
        var childGanglion = new MockGanglion("child-g", Set.of("io.casehub.service.error"),
                FixedDetectionResult.detected("child-g", 0.9));
        var childDef = new SituationDefinition("service-health", Set.of("io.casehub.service.error"),
                null, null, new ChainMode.Or(Set.of("child-g")),
                new TriggerAction.NotifyOnly(), null);

        var l1Def = new SituationDefinition("degradation",
                Set.of("ras.situation.triggered"),
                null, null, new ChainMode.Count("l1-sw", 2),
                new TriggerAction.NotifyOnly(), null);
        var l2Def = new SituationDefinition("escalation",
                Set.of("ras.situation.triggered"),
                null, null, new ChainMode.Or(Set.of("l2-sw")),
                new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var l1Mapping = Map.of(SituationChangeEvent.ChangeType.TRIGGERED, DetectionSignal.DETECTED);
        var l2Mapping = Map.of(SituationChangeEvent.ChangeType.TRIGGERED, DetectionSignal.DETECTED);

        SituationDefinitionProvider provider = new SituationDefinitionProvider() {
            @Override public List<SituationRegistration> registrations() {
                return List.of(
                        new SituationRegistration(childDef),
                        new SituationRegistration(l1Def),
                        new SituationRegistration(l2Def));
            }
            @Override public List<GanglionDescriptor> ganglionDescriptors() {
                return List.of(
                        new GanglionDescriptor.SituationWatcher("l1-sw", l1Mapping, Map.of()),
                        new GanglionDescriptor.SituationWatcher("l2-sw", l2Mapping, Map.of()));
            }
        };

        assertThatIllegalStateException().isThrownBy(() ->
                        new SituationDefinitionRegistry(List.of(provider), List.of(childGanglion)))
                .withMessageContaining("Cycle");
    }

    private static RasMetrics createMetrics(SituationDefinitionRegistry registry) {
        var metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(new SimpleMeterRegistry());
        metrics.init();
        return metrics;
    }

    private static class BridgingChangeEvent implements Event<SituationChangeEvent> {
        private final CopyOnWriteArrayList<SituationChangeEvent> fired = new CopyOnWriteArrayList<>();
        private SituationEvaluator evaluator;
        private SituationDefinitionRegistry registry;

        void init(SituationEvaluator evaluator, SituationDefinitionRegistry registry) {
            this.evaluator = evaluator;
            this.registry = registry;
        }

        List<SituationChangeEvent> allFired() { return List.copyOf(fired); }

        @Override public void fire(SituationChangeEvent event) { fired.add(event); }

        @Override
        @SuppressWarnings("unchecked")
        public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event) {
            fired.add(event);
            if (evaluator != null && registry != null) {
                String changeTypeLower = event.changeType().name().toLowerCase();
                CloudEvent bridged = CloudEventBuilder.v1()
                        .withId(UUID.randomUUID().toString())
                        .withType("ras.situation." + changeTypeLower)
                        .withSource(URI.create("ras://bridge"))
                        .withSubject(event.situationId())
                        .withTime(OffsetDateTime.now())
                        .withExtension("tenancyid", event.tenancyId())
                        .withExtension("situationid", event.situationId())
                        .withExtension("correlationkey", event.correlationKey())
                        .withExtension("changetype", event.changeType().name())
                        .build();
                for (SituationRegistration reg : registry.findByEventType(bridged.getType())) {
                    String corrKey = DefaultCorrelationKeyExtractor.INSTANCE.extract(bridged);
                    evaluator.evaluate(bridged, reg.definition(), corrKey, event.tenancyId());
                }
            }
            return (CompletionStage<U>) CompletableFuture.completedFuture(event);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
            return fireAsync(event);
        }

        @Override public Event<SituationChangeEvent> select(Annotation... qualifiers) { return this; }
        @Override public <U extends SituationChangeEvent> Event<U> select(Class<U> subtype, Annotation... qualifiers) { return null; }
        @Override public <U extends SituationChangeEvent> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { return null; }
    }
}
