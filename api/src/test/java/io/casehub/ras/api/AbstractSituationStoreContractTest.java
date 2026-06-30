package io.casehub.ras.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

public abstract class AbstractSituationStoreContractTest {

    protected SituationStore store;

    protected static final Instant T1 = Instant.parse("2026-06-20T10:00:00Z");
    protected static final Instant T2 = Instant.parse("2026-06-20T10:05:00Z");
    protected static final Instant T3 = Instant.parse("2026-06-20T10:10:00Z");

    protected abstract SituationStore createStore();

    @BeforeEach
    void setUpStore() {
        store = createStore();
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
        assertThat(found).isPresent();
        var result = found.get();
        assertThat(result.situationId()).isEqualTo("sit-1");
        assertThat(result.correlationKey()).isEqualTo("key-1");
        assertThat(result.tenancyId()).isEqualTo("tenant-a");
        assertThat(result.firstSignal()).isEqualTo(T1);
        assertThat(result.lastSignal()).isEqualTo(T1);
        assertThat(result.detections()).isEmpty();
        assertThat(result.storeVersion()).isPresent();
    }

    @Test
    void saveUpdatesExisting() {
        var ctx1 = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx1).await().indefinitely();

        var found1 = store.find("sit-1", "key-1", "tenant-a").await().indefinitely().orElseThrow();
        var detection = new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of());
        var ctx2 = found1.withDetection(detection, T2);
        store.save(ctx2).await().indefinitely();

        var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(found).isPresent();
        assertThat(found.get().detections()).hasSize(1);
        assertThat(found.get().lastSignal()).isEqualTo(T2);
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

    // --- Claim tests ---

    @Test
    void tryClaimTriggerSucceedsAfterSave() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();

        boolean claimed = store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1)
                .await().indefinitely();
        assertThat(claimed).isTrue();
    }

    @Test
    void tryClaimTriggerBlocksSecondClaim() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();

        store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1).await().indefinitely();
        boolean second = store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1)
                .await().indefinitely();
        assertThat(second).isFalse();
    }

    @Test
    void resetTriggerClaimAllowsReClaim() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();

        store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1).await().indefinitely();
        store.resetTriggerClaim("sit-1", "key-1", "tenant-a").await().indefinitely();

        boolean reclaimed = store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1)
                .await().indefinitely();
        assertThat(reclaimed).isTrue();
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
}
