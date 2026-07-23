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
class JpaSituationStoreTest extends AbstractSituationStoreContractTest {

    @Inject
    JpaSituationStore jpaStore;

    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T23:59:59Z");

    @Override
    protected SituationStore createStore() {
        return jpaStore;
    }

    @BeforeEach
    void cleanUpData() {
        store.removeExpired(FAR_FUTURE);
    }

    // --- JPA-specific tests ---

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

        store.save(ctx);

        var found = store.find("sit-1", "key-1", "tenant-a");
        assertThat(found).isPresent();
        var detections = found.get().detections();
        assertThat(detections).hasSize(3);
        assertThat(detections.get(0).result().ganglionId()).isEqualTo("g1");
        assertThat(detections.get(0).result().evidence()).containsEntry("key1", "value1");
        assertThat(detections.get(1).result().signal()).isEqualTo(DetectionSignal.WEAK);
        assertThat(detections.get(2).result().evidence()).containsEntry("flag", true);
    }

    @Test
    void findPopulatesStoreVersion() {
        var ctx = SituationContext.initial("sit-v", "key-1", "tenant-a", T1);
        store.save(ctx);

        var found = store.find("sit-v", "key-1", "tenant-a");
        assertThat(found).isPresent();
        assertThat(found.get().storeVersion()).isPresent();
        assertThat(found.get().storeVersion().getAsLong()).isEqualTo(0L);
    }

    @Test
    void saveIncrementsStoreVersion() {
        var ctx = SituationContext.initial("sit-v", "key-1", "tenant-a", T1);
        store.save(ctx);

        var found1 = store.find("sit-v", "key-1", "tenant-a").orElseThrow();
        var detection = new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of());
        var modified = found1.withDetection(detection, T2);
        store.save(modified);

        var found2 = store.find("sit-v", "key-1", "tenant-a").orElseThrow();
        assertThat(found2.storeVersion().getAsLong()).isEqualTo(1L);
    }

    @Test
    void saveThrowsConflictOnStaleVersion() {
        var ctx = SituationContext.initial("sit-v", "key-1", "tenant-a", T1);
        store.save(ctx);

        var found = store.find("sit-v", "key-1", "tenant-a").orElseThrow();
        var d1 = new DetectionResult("g1", 0.5, DetectionSignal.WEAK, Map.of());
        var concurrent = found.withDetection(d1, T2);
        store.save(concurrent);

        var detection = new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of());
        var stale = found.withDetection(detection, T2);
        assertThatThrownBy(() -> store.save(stale))
                .isInstanceOf(SituationConflictException.class);
    }

    @Test
    void saveThrowsConflictWhenEntityCreatedByAnotherWriter() {
        var ctx1 = SituationContext.initial("sit-race", "key-1", "tenant-a", T1);
        store.save(ctx1);

        var ctx2 = SituationContext.initial("sit-race", "key-1", "tenant-a", T1);
        assertThatThrownBy(() -> store.save(ctx2))
                .isInstanceOf(SituationConflictException.class);
    }

    @Test
    void tryClaimTriggerReturnsFalseForMissingSituation() {
        boolean claimed = store.tryClaimTrigger("nonexistent", "key-1", "tenant-a", T1)
                ;
        assertThat(claimed).isFalse();
    }

    @Test
    void saveThrowsConflictWhenEntityRemovedByAnotherWriter() {
        var ctx = SituationContext.initial("sit-gone", "key-1", "tenant-a", T1);
        store.save(ctx);
        var found = store.find("sit-gone", "key-1", "tenant-a").orElseThrow();

        store.remove("sit-gone", "key-1", "tenant-a");

        var detection = new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of());
        var stale = found.withDetection(detection, T2);
        assertThatThrownBy(() -> store.save(stale))
                .isInstanceOf(SituationConflictException.class);
    }
}
