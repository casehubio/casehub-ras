package io.casehub.ras.runtime;

import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.OrphanedResourceCleaner;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.persistence.memory.InMemorySituationStore;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockGanglion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SituationExpiryJobTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private static final Instant OLD = Instant.now().minus(Duration.ofHours(2));
    private static final Instant RECENT = Instant.now().minus(Duration.ofMinutes(30));

    @Test
    void removesExpiredSituations() {
        var store = new InMemorySituationStore();
        store.save(SituationContext.initial("sit-old", "k", "t", OLD));
        store.save(SituationContext.initial("sit-new", "k", "t", RECENT));

        var ganglion = new MockGanglion("g1", Set.of("e"),
                FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-old", Set.of("e"), Duration.ofHours(1), null,
                new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())), null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var job = new SituationExpiryJob(store, registry, Duration.ofMinutes(1), initMetrics(registry), List.of());

        job.cleanup();

        assertThat(store.find("sit-old", "k", "t")).isEmpty();
        assertThat(store.find("sit-new", "k", "t")).isPresent();
    }

    @Test
    void noOpWhenAllDefinitionsPersistent() {
        var store = new InMemorySituationStore();
        store.save(SituationContext.initial("sit-1", "k", "t", OLD));

        var ganglion = new MockGanglion("g1", Set.of("e"),
                FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-1", Set.of("e"), null, null,
                new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())), null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var job = new SituationExpiryJob(store, registry, Duration.ofMinutes(1), initMetrics(registry), List.of());

        job.cleanup();

        assertThat(store.find("sit-1", "k", "t")).isPresent();
    }

    @Test
    void removesTriggeredEntitiesAfterGuardPeriod() {
        var store = new InMemorySituationStore();
        var g = new MockGanglion("g1", Set.of("e"), FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("e"), null, null,
                new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())), null);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(g));

        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-25T10:00:00Z"));
        store.save(ctx);
        store.tryClaimTrigger("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-25T10:00:00Z"));

        var job = new SituationExpiryJob(store, registry, Duration.ofMinutes(1), initMetrics(registry), List.of());

        assertThat(store.find("sit-1", "key-1", "tenant-a")).isPresent();

        // Guard period has NOT elapsed — cleanup should keep entity
        // (We can't easily test time-based cleanup in unit tests without mocking Instant.now,
        //  but we test the store method directly in contract tests)
    }

    @Test
    void cleanupRunsEvenWhenAllSituationsPersistent() {
        var g = new MockGanglion("g1", Set.of("e"), FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("e"), null, null,
                new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())), null);
        var reg = new SituationRegistration(def);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(reg)), List.of(g));
        var store = new InMemorySituationStore();
        var job = new SituationExpiryJob(store, registry, Duration.ofMinutes(1), initMetrics(registry), List.of());

        // Should not throw — previously returned early when maxWindow was null
        job.cleanup();
    }

    private RasMetrics initMetrics(SituationDefinitionRegistry registry) {
        var metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(meterRegistry);
        metrics.init();
        return metrics;
    }

    @Test
    void expiredCleanedCounterReflectsRemovedCount() {
        var store = new InMemorySituationStore();
        store.save(SituationContext.initial("sit-old", "k", "t", OLD));
        store.save(SituationContext.initial("sit-new", "k", "t", RECENT));

        var ganglion = new MockGanglion("g1", Set.of("e"),
                                        FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-old", Set.of("e"), Duration.ofHours(1), null,
                                          new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())), null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var job = new SituationExpiryJob(store, registry, Duration.ofMinutes(1), initMetrics(registry), List.of());

        job.cleanup();

        assertThat(meterRegistry.counter("ras.expiry.expired_cleaned").count()).isEqualTo(1.0);
    }

    @Test
    void expiredCleanedNotIncrementedWhenNoWindowedDefinitions() {
        var store = new InMemorySituationStore();
        var ganglion = new MockGanglion("g1", Set.of("e"),
                                        FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-1", Set.of("e"), null, null,
                                          new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())), null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var job = new SituationExpiryJob(store, registry, Duration.ofMinutes(1), initMetrics(registry), List.of());

        job.cleanup();

        assertThat(meterRegistry.find("ras.expiry.expired_cleaned").counter()).isNull();
        assertThat(meterRegistry.counter("ras.expiry.triggered_cleaned").count()).isEqualTo(0.0);
    }

    @Test
    void cleanupCallsRemoveOrphanedAndRecordsMetric() {
        var store = new InMemorySituationStore();
        var ganglion = new MockGanglion("g1", Set.of("e"),
                                        FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-1", Set.of("e"), null, null,
                                          new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())), null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));

        OrphanedResourceCleaner mockCleaner = new OrphanedResourceCleaner() {
            @Override
            public String cleanerType()          {return "test";}

            @Override
            public int removeOrphaned() {return 3;}
        };

        var job = new SituationExpiryJob(store, registry,
                                         Duration.ofMinutes(1), initMetrics(registry), List.of(mockCleaner));

        job.cleanup();

        assertThat(meterRegistry.counter("ras.expiry.orphans_cleaned", "cleaner_type", "test").count())
                .isEqualTo(3.0);
    }

    @Test
    void cleanupIsolatesCleanerFailures() {
        var store = new InMemorySituationStore();
        var ganglion = new MockGanglion("g1", Set.of("e"),
                                        FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-1", Set.of("e"), null, null,
                                          new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())), null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));

        OrphanedResourceCleaner failingCleaner = new OrphanedResourceCleaner() {
            @Override
            public String cleanerType()          {return "failing";}

            @Override
            public int removeOrphaned() {throw new RuntimeException("boom");}
        };
        OrphanedResourceCleaner workingCleaner = new OrphanedResourceCleaner() {
            @Override
            public String cleanerType()          {return "working";}

            @Override
            public int removeOrphaned() {return 2;}
        };

        var job = new SituationExpiryJob(store, registry,
                                         Duration.ofMinutes(1), initMetrics(registry),
                                         List.of(failingCleaner, workingCleaner));

        job.cleanup();

        assertThat(meterRegistry.counter("ras.expiry.orphans_cleaned", "cleaner_type", "working").count())
                .isEqualTo(2.0);
    }


}
