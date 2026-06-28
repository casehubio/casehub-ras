package io.casehub.ras.persistence.jpa;

import io.casehub.ras.api.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class JpaSituationStoreTest {

    @Inject
    JpaSituationStore store;

    private static final Instant T1 = Instant.parse("2026-06-28T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-28T10:05:00Z");
    private static final Instant T3 = Instant.parse("2026-06-28T10:10:00Z");

    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T23:59:59Z");

    @BeforeEach
    void cleanUp() {
        store.removeExpired(FAR_FUTURE).await().indefinitely();
    }

    @Test
    void saveAndFindRoundTrip() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        var detection = new DetectionResult("g1", 0.8, DetectionSignal.DETECTED,
                Map.of("sensor", "temp-1", "value", 42.5));
        ctx = ctx.withDetection(detection, T2);

        store.save(ctx).await().indefinitely();

        var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(found).isPresent();
        var result = found.get();
        assertThat(result.situationId()).isEqualTo("sit-1");
        assertThat(result.correlationKey()).isEqualTo("key-1");
        assertThat(result.tenancyId()).isEqualTo("tenant-a");
        assertThat(result.firstSignal()).isEqualTo(T1);
        assertThat(result.lastSignal()).isEqualTo(T2);
        assertThat(result.detections()).hasSize(1);
        assertThat(result.detections().get(0).result().ganglionId()).isEqualTo("g1");
        assertThat(result.detections().get(0).result().confidence()).isEqualTo(0.8);
        assertThat(result.detections().get(0).result().signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.detections().get(0).result().evidence())
                .containsEntry("sensor", "temp-1");
        assertThat(result.detections().get(0).eventTime()).isEqualTo(T2);
    }

    @Test
    void saveUpdatesExisting() {
        var ctx1 = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx1).await().indefinitely();

        var detection = new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of());
        var ctx2 = ctx1.withDetection(detection, T2);
        store.save(ctx2).await().indefinitely();

        var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(found).isPresent();
        assertThat(found.get().detections()).hasSize(1);
        assertThat(found.get().lastSignal()).isEqualTo(T2);
    }

    @Test
    void findReturnsEmptyForUnknownKey() {
        var result = store.find("unknown", "key-1", "tenant-a").await().indefinitely();
        assertThat(result).isEmpty();
    }

    @Test
    void removeDeletesByNaturalKey() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        store.save(ctx).await().indefinitely();
        store.remove("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(store.find("sit-1", "key-1", "tenant-a").await().indefinitely()).isEmpty();
    }

    @Test
    void removeNonExistentKeyIsNoOp() {
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
    void detectionsJsonRoundTrip() {
        var ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        var d1 = new DetectionResult("g1", 0.9, DetectionSignal.DETECTED,
                Map.of("key1", "value1", "count", 42));
        var d2 = new DetectionResult("g2", 0.3, DetectionSignal.WEAK, Map.of());
        var d3 = new DetectionResult("g3", 0.0, DetectionSignal.NOISE, Map.of("flag", true));
        ctx = ctx.withDetection(d1, T1);
        ctx = ctx.withDetection(d2, T2);
        ctx = ctx.withDetection(d3, T3);

        store.save(ctx).await().indefinitely();

        var found = store.find("sit-1", "key-1", "tenant-a").await().indefinitely();
        assertThat(found).isPresent();
        var detections = found.get().detections();
        assertThat(detections).hasSize(3);
        assertThat(detections.get(0).result().ganglionId()).isEqualTo("g1");
        assertThat(detections.get(0).result().evidence()).containsEntry("key1", "value1");
        assertThat(detections.get(1).result().signal()).isEqualTo(DetectionSignal.WEAK);
        assertThat(detections.get(2).result().evidence()).containsEntry("flag", true);
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
