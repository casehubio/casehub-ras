package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.casehub.ras.persistence.memory.InMemorySituationStore;
import io.casehub.ras.testing.FixedDetectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DefaultSituationSourceTest {

    private InMemorySituationStore store;
    private DefaultSituationSource source;

    @BeforeEach
    void setUp() {
        store = new InMemorySituationStore();
        source = new DefaultSituationSource(store);
    }

    @Test
    void returnsActiveSituationsForTenant() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-25T10:00:00Z"))
                .withDetection(new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of()),
                               Instant.parse("2026-06-25T10:00:00Z"));
        store.save(ctx);

        var active = source.activeSituations("tenant-a");
        assertThat(active).hasSize(1);
        assertThat(active.get(0).situationId()).isEqualTo("sit-1");
        assertThat(active.get(0).confidence()).isEqualTo(0.8);
        assertThat(active.get(0).since()).isEqualTo(Instant.parse("2026-06-25T10:00:00Z"));
    }

    @Test
    void excludesTriggeredSituations() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-25T10:00:00Z"));
        store.save(ctx);
        store.tryClaimTrigger("sit-1", "key-1", "tenant-a",
                Instant.parse("2026-06-25T10:00:00Z"));

        assertThat(source.activeSituations("tenant-a")).isEmpty();
    }

    @Test
    void emptyForUnknownTenant() {
        assertThat(source.activeSituations("unknown")).isEmpty();
    }
}
