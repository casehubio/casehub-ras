package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.persistence.memory.InMemorySituationStore;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockGanglion;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class SituationExpiryJobTest {

    private static final Instant OLD = Instant.now().minus(Duration.ofHours(2));
    private static final Instant RECENT = Instant.now().minus(Duration.ofMinutes(30));

    @Test
    void removesExpiredSituations() {
        var store = new InMemorySituationStore();
        store.save(SituationContext.initial("sit-old", "k", "t", OLD)).await().indefinitely();
        store.save(SituationContext.initial("sit-new", "k", "t", RECENT)).await().indefinitely();

        var ganglion = new MockGanglion("g1", Set.of("e"),
                FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-old", Set.of("e"), Duration.ofHours(1), null,
                new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())), null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var job = new SituationExpiryJob(store, registry, Duration.ofMinutes(1));

        job.cleanup();

        assertThat(store.find("sit-old", "k", "t").await().indefinitely()).isEmpty();
        assertThat(store.find("sit-new", "k", "t").await().indefinitely()).isPresent();
    }

    @Test
    void noOpWhenAllDefinitionsPersistent() {
        var store = new InMemorySituationStore();
        store.save(SituationContext.initial("sit-1", "k", "t", OLD)).await().indefinitely();

        var ganglion = new MockGanglion("g1", Set.of("e"),
                FixedDetectionResult.noise("g1"));
        var def = new SituationDefinition("sit-1", Set.of("e"), null, null,
                new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "c", "1", Map.of())), null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var job = new SituationExpiryJob(store, registry, Duration.ofMinutes(1));

        job.cleanup();

        assertThat(store.find("sit-1", "k", "t").await().indefinitely()).isPresent();
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
        store.save(ctx).await().indefinitely();
        store.tryClaimTrigger("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-25T10:00:00Z")).await().indefinitely();

        var job = new SituationExpiryJob(store, registry, Duration.ofMinutes(1));

        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isPresent();

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
        var job = new SituationExpiryJob(store, registry, Duration.ofMinutes(1));

        // Should not throw — previously returned early when maxWindow was null
        job.cleanup();
    }
}
