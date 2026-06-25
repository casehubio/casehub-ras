package io.casehub.ras.memory;

import io.casehub.ras.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class InMemorySituationStoreTest {

    private InMemorySituationStore store;

    private static final Instant T1 = Instant.parse("2026-06-20T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-20T10:05:00Z");
    private static final Instant T3 = Instant.parse("2026-06-20T10:10:00Z");

    @BeforeEach
    void setUp() {
        store = new InMemorySituationStore();
    }

    @Test
    void findReturnsEmptyWhenNotPresent() {
        var result = store.find("unknown", "key-1", "tenant-a").await().indefinitely();
        assertThat(result).isEmpty();
    }

    @Test
    void saveAndFindRoundTrip() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();

        var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(found).isPresent().contains(ctx);
    }

    @Test
    void saveIsUpsert() {
        var ctx1 = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx1).await().indefinitely();

        var detection = new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of());
        var ctx2 = ctx1.withDetection(detection, T2);
        store.save(ctx2).await().indefinitely();

        var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(found).isPresent();
        assertThat(found.get().detections()).hasSize(1);
    }

    @Test
    void tenantIsolation() {
        var ctxA = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        var ctxB = SituationContext.initial("sit-1", "key-1", "tenant-b", T1);
        store.save(ctxA).await().indefinitely();
        store.save(ctxB).await().indefinitely();

        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely())
                .isPresent().get().extracting(SituationContext::tenancyId).isEqualTo("tenant-a");
        assertThat(store.find("sit-1", "key-1", "tenant-b").await().indefinitely())
                .isPresent().get().extracting(SituationContext::tenancyId).isEqualTo("tenant-b");
    }

    @Test
    void removeDeletesEntry() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();
        store.remove("sit-1", "key-1", "tenant-a").await().indefinitely();

        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
    }

    @Test
    void removeNonExistentIsNoOp() {
        assertThatNoException().isThrownBy(
                () -> store.remove("nonexistent", "key-1", "tenant-a").await().indefinitely());
    }

    @Test
    void removeExpiredEvictsOldEntries() {
        var old = SituationContext.initial("old-sit", "key-1", "tenant-a", T1);
        var recent = SituationContext.initial("recent-sit", "key-1", "tenant-a", T3);
        store.save(old).await().indefinitely();
        store.save(recent).await().indefinitely();

        store.removeExpired(T2).await().indefinitely();

        assertThat(store.find("old-sit", "key-1", "tenant-a").await().indefinitely()).isEmpty();
        assertThat(store.find("recent-sit", "key-1", "tenant-a").await().indefinitely()).isPresent();
    }

    @Test
    void removeExpiredIsCrossTenant() {
        var ctxA = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        var ctxB = SituationContext.initial("sit-2", "key-1", "tenant-b", T1);
        store.save(ctxA).await().indefinitely();
        store.save(ctxB).await().indefinitely();

        store.removeExpired(T2).await().indefinitely();

        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
        assertThat(store.find("sit-2", "key-1", "tenant-b").await().indefinitely()).isEmpty();
    }

    @Test
    void correlationKeyIsolation() {
        var ctx1 = SituationContext.initial("sit-1", "machine-1", "tenant-a", T1);
        var ctx2 = SituationContext.initial("sit-1", "machine-2", "tenant-a", T1);
        store.save(ctx1).await().indefinitely();
        store.save(ctx2).await().indefinitely();

        assertThat(store.find("sit-1", "machine-1", "tenant-a").await().indefinitely())
                .isPresent().get().extracting(SituationContext::correlationKey).isEqualTo("machine-1");
        assertThat(store.find("sit-1", "machine-2", "tenant-a").await().indefinitely())
                .isPresent().get().extracting(SituationContext::correlationKey).isEqualTo("machine-2");
    }
}
