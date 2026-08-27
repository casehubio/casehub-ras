package io.casehub.ras.runtime;

import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.persistence.memory.InMemorySituationStore;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockCaseTrigger;
import io.casehub.ras.testing.MockGanglion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineCheckJobTest {

    private InMemorySituationStore store;
    private MockCaseTrigger caseTrigger;
    private TestChangeEvent changeEvent;
    private RasMetrics metrics;

    @BeforeEach
    void setUp() {
        store = new InMemorySituationStore();
        caseTrigger = new MockCaseTrigger();
        changeEvent = new TestChangeEvent();
    }

    private DeadlineCheckJob buildJob(SituationDefinition def, List<MockGanglion> ganglia) {
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.copyOf(ganglia));
        var meterRegistry = new SimpleMeterRegistry();
        metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(meterRegistry);
        metrics.init();
        var policy = new DefaultRasTriggerPolicy();
        var evaluator = new SituationEvaluator(store, policy, caseTrigger, registry, 3, changeEvent, metrics);
        return new DeadlineCheckJob(store, registry, evaluator, metrics);
    }

    @Test
    void check_triggers_expired_deadline() {
        var ganglion = new MockGanglion("g1", Set.of("ras.situation.triggered"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit", Set.of("ras.situation.triggered"),
                null, null, new ChainMode.Count("g1", 3),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null, null, null, Map.of(), null, Duration.ofMinutes(5));
        var job = buildJob(def, List.of(ganglion));

        store.save(SituationContext.initial("sit", "key", "t",
                Instant.now().minus(Duration.ofMinutes(10))));

        job.check();

        assertThat(caseTrigger.firedCases()).hasSize(1);
        assertThat(changeEvent.firedEvents()).anyMatch(
                e -> e.changeType() == SituationChangeEvent.ChangeType.TRIGGERED);
    }

    @Test
    void check_skips_non_expired_deadline() {
        var ganglion = new MockGanglion("g1", Set.of("ras.situation.triggered"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit", Set.of("ras.situation.triggered"),
                null, null, new ChainMode.Count("g1", 3),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null, null, null, Map.of(), null, Duration.ofMinutes(30));
        var job = buildJob(def, List.of(ganglion));

        store.save(SituationContext.initial("sit", "key", "t",
                Instant.now().minus(Duration.ofMinutes(5))));

        job.check();

        assertThat(caseTrigger.firedCases()).isEmpty();
        assertThat(changeEvent.firedEvents()).isEmpty();
    }

    @Test
    void check_skips_situations_without_deadline() {
        var ganglion = new MockGanglion("g1", Set.of("ras.situation.triggered"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit", Set.of("ras.situation.triggered"),
                null, null, new ChainMode.Count("g1", 3),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null);
        var job = buildJob(def, List.of(ganglion));

        store.save(SituationContext.initial("sit", "key", "t",
                Instant.now().minus(Duration.ofHours(1))));

        job.check();

        assertThat(caseTrigger.firedCases()).isEmpty();
    }

    private static class TestChangeEvent implements Event<SituationChangeEvent> {
        private final CopyOnWriteArrayList<SituationChangeEvent> fired = new CopyOnWriteArrayList<>();

        List<SituationChangeEvent> firedEvents() { return fired; }

        @Override public void fire(SituationChangeEvent event) { fired.add(event); }

        @Override
        @SuppressWarnings("unchecked")
        public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event) {
            fired.add(event);
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
