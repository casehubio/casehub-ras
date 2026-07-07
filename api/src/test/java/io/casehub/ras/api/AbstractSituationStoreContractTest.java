package io.casehub.ras.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
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

    // --- Trigger metadata tests ---

    @Test
    void tryClaimTriggerStampsTriggerMetadata() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();
        Instant triggerTime = T2;
        store.tryClaimTrigger("sit-1", "key-1", "tenant-a", triggerTime).await().indefinitely();
        var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely().orElseThrow();
        assertThat(found.lastTriggered()).isEqualTo(triggerTime);
        assertThat(found.triggerCount()).isEqualTo(1);
    }

    @Test
    void tryClaimTriggerIncrementsCountOnSubsequentClaims() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();
        store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1).await().indefinitely();
        store.resetTriggerClaim("sit-1", "key-1", "tenant-a").await().indefinitely();
        store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T2).await().indefinitely();
        var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely().orElseThrow();
        assertThat(found.lastTriggered()).isEqualTo(T2);
        assertThat(found.triggerCount()).isEqualTo(2);
    }

    @Test
    void saveAfterClaimPreservesTriggerMetadata() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        var saved = store.save(ctx).await().indefinitely();
        store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1).await().indefinitely();
        var modified = saved.withDetection(
                new DetectionResult("g1", 0.9, DetectionSignal.DETECTED, Map.of()), T2);
        store.save(modified).await().indefinitely();
        var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely().orElseThrow();
        assertThat(found.lastTriggered()).isEqualTo(T1);
        assertThat(found.triggerCount()).isEqualTo(1);
        assertThat(found.detections()).hasSize(1);
    }

    @Test
    void removeTriggeredBeforeRemovesOldTriggeredEntities() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();
        store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1).await().indefinitely();
        store.removeTriggeredBefore(T2).await().indefinitely();
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
    }

    @Test
    void removeTriggeredBeforeKeepsRecentTriggeredEntities() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();
        store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T2).await().indefinitely();
        store.removeTriggeredBefore(T1).await().indefinitely();
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isPresent();
    }

    @Test
    void removeTriggeredBeforeKeepsNonTriggeredEntities() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();
        store.removeTriggeredBefore(T3).await().indefinitely();
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isPresent();
    }

    @Test
    void findActiveReturnsByTenancy() {
        var ctxA = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        var ctxB = SituationContext.initial("sit-1", "key-1", "tenant-b", T1);
        store.save(ctxA).await().indefinitely();
        store.save(ctxB).await().indefinitely();
        var active = store.findActive("tenant-a").await().indefinitely();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).tenancyId()).isEqualTo("tenant-a");
    }

    @Test
    void findActiveExcludesTriggeredEntities() {
        var ctx1 = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        var ctx2 = SituationContext.initial("sit-2", "key-1", "tenant-a", T1);
        store.save(ctx1).await().indefinitely();
        store.save(ctx2).await().indefinitely();
        store.tryClaimTrigger("sit-1", "key-1", "tenant-a", T1).await().indefinitely();
        var active = store.findActive("tenant-a").await().indefinitely();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).situationId()).isEqualTo("sit-2");
    }

    @Test
    void findActiveEmptyForUnknownTenant() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();
        assertThat(store.findActive("tenant-x").await().indefinitely()).isEmpty();
    }

    @Test
    void saveAndFindRoundTripWithTriggerFields() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();
        var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely().orElseThrow();
        assertThat(found.lastTriggered()).isNull();
        assertThat(found.triggerCount()).isZero();
        assertThat(found.storeVersion()).isPresent();
    }

    // --- removeAllForSituation tests ---

    @Test
    void removeAllForSituation_removes_all_matching_entries() {
        var ctx1 = new SituationContext("sit-A", "key-1", "tenant-a",
                T1, T1, List.of(), OptionalLong.empty(), null, 0);
        var ctx2 = new SituationContext("sit-A", "key-2", "tenant-a",
                T1, T1, List.of(), OptionalLong.empty(), null, 0);
        var ctx3 = new SituationContext("sit-B", "key-1", "tenant-a",
                T1, T1, List.of(), OptionalLong.empty(), null, 0);

        store.save(ctx1).await().indefinitely();
        store.save(ctx2).await().indefinitely();
        store.save(ctx3).await().indefinitely();

        store.removeAllForSituation("sit-A").await().indefinitely();

        assertThat(store.find("sit-A", "key-1", "tenant-a").await().indefinitely()).isEmpty();
        assertThat(store.find("sit-A", "key-2", "tenant-a").await().indefinitely()).isEmpty();
        assertThat(store.find("sit-B", "key-1", "tenant-a").await().indefinitely()).isPresent();
    }

    @Test
    void removeAllForSituation_noop_when_no_matches() {
        store.removeAllForSituation("nonexistent").await().indefinitely();
        // no exception
    }

    @Test
    void removeAllForSituation_removes_across_correlation_keys_and_tenants() {
        var ctx1 = new SituationContext("sit-X", "key-1", "tenant-a",
                T1, T1, List.of(), OptionalLong.empty(), null, 0);
        var ctx2 = new SituationContext("sit-X", "key-2", "tenant-a",
                T1, T1, List.of(), OptionalLong.empty(), null, 0);
        var ctx3 = new SituationContext("sit-X", "key-1", "tenant-b",
                T1, T1, List.of(), OptionalLong.empty(), null, 0);
        var ctx4 = new SituationContext("sit-Y", "key-1", "tenant-a",
                T1, T1, List.of(), OptionalLong.empty(), null, 0);

        store.save(ctx1).await().indefinitely();
        store.save(ctx2).await().indefinitely();
        store.save(ctx3).await().indefinitely();
        store.save(ctx4).await().indefinitely();

        store.removeAllForSituation("sit-X").await().indefinitely();

        assertThat(store.find("sit-X", "key-1", "tenant-a").await().indefinitely()).isEmpty();
        assertThat(store.find("sit-X", "key-2", "tenant-a").await().indefinitely()).isEmpty();
        assertThat(store.find("sit-X", "key-1", "tenant-b").await().indefinitely()).isEmpty();
        assertThat(store.find("sit-Y", "key-1", "tenant-a").await().indefinitely()).isPresent();
    }
}
