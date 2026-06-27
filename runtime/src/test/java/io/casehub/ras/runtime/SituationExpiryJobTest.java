package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.memory.InMemorySituationStore;
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
                new CaseTriggerConfig("ns", "c", "1", Map.of()));
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var job = new SituationExpiryJob(store, registry);

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
                new CaseTriggerConfig("ns", "c", "1", Map.of()));
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))), List.of(ganglion));
        var job = new SituationExpiryJob(store, registry);

        job.cleanup();

        assertThat(store.find("sit-1", "k", "t").await().indefinitely()).isPresent();
    }
}
