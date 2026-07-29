package io.casehub.ras.persistence.jpa;

import io.casehub.ras.api.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SituationEventRecorderTest {

    @Inject SituationEventRecorder recorder;
    @Inject JpaSituationQueryService queryService;

    private static final Instant T1 = Instant.parse("2026-06-20T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-20T11:00:00Z");

    @BeforeEach
    @Transactional
    void cleanUp() {
        queryService.removeEventsBefore(Instant.parse("9999-12-31T23:59:59Z"));
    }

    @Test
    void recordsTriggeredEvent() {
        SituationContext ctx = new SituationContext("sit-1", "key-1", "tenant-a",
                T1, T2, List.of(
                        new TimestampedDetection(
                                new DetectionResult("g1", 0.85, DetectionSignal.DETECTED,
                                        Map.of("temp", 95.2)), T2)),
                OptionalLong.of(1L), null, 0);

        SituationChangeEvent changeEvent = new SituationChangeEvent(
                "tenant-a", "sit-1", "key-1",
                SituationChangeEvent.ChangeType.TRIGGERED, ctx);

        recorder.onSituationChange(changeEvent);

        var history = queryService.history("tenant-a", T1, T2.plusSeconds(1));
        assertThat(history).hasSize(1);
        var event = history.get(0);
        assertThat(event.situationId()).isEqualTo("sit-1");
        assertThat(event.correlationKey()).isEqualTo("key-1");
        assertThat(event.tenancyId()).isEqualTo("tenant-a");
        assertThat(event.changeType()).isEqualTo(SituationChangeEvent.ChangeType.TRIGGERED);
        assertThat(event.firstSeen()).isEqualTo(T1);
        assertThat(event.confidence()).isEqualTo(0.85);
        assertThat(event.detectionCount()).isEqualTo(1);
        assertThat(event.evidence()).containsEntry("temp", 95.2);
    }

    @Test
    void extractsMaxQualifyingConfidence() {
        SituationContext ctx = new SituationContext("sit-1", "key-1", "tenant-a",
                T1, T2, List.of(
                        new TimestampedDetection(
                                new DetectionResult("g1", 0.3, DetectionSignal.WEAK, Map.of()), T1),
                        new TimestampedDetection(
                                new DetectionResult("g2", 0.9, DetectionSignal.DETECTED,
                                        Map.of("best", true)), T2),
                        new TimestampedDetection(
                                new DetectionResult("g3", 0.0, DetectionSignal.NOISE, Map.of()), T2)),
                OptionalLong.of(1L), null, 0);

        SituationChangeEvent changeEvent = new SituationChangeEvent(
                "tenant-a", "sit-1", "key-1",
                SituationChangeEvent.ChangeType.RESOLVED, ctx);

        recorder.onSituationChange(changeEvent);

        var history = queryService.history("tenant-a", T1, T2.plusSeconds(1));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).confidence()).isEqualTo(0.9);
        assertThat(history.get(0).evidence()).containsEntry("best", true);
    }

    @Test
    void handlesEmptyDetections() {
        SituationContext ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);

        SituationChangeEvent changeEvent = new SituationChangeEvent(
                "tenant-a", "sit-1", "key-1",
                SituationChangeEvent.ChangeType.DISCARDED, ctx);

        recorder.onSituationChange(changeEvent);

        var history = queryService.history("tenant-a", T1, T1.plusSeconds(1));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).confidence()).isEqualTo(0.0);
        assertThat(history.get(0).detectionCount()).isZero();
        assertThat(history.get(0).evidence()).isEmpty();
    }

    @Test
    void preservesMetadata() {
        SituationContext ctx = SituationContext.initial("sit-1", "key-1", "tenant-a", T1);
        Map<String, Object> metadata = Map.of("policyVersion", "v2", "score", 42);

        SituationChangeEvent changeEvent = new SituationChangeEvent(
                "tenant-a", "sit-1", "key-1",
                SituationChangeEvent.ChangeType.TRIGGERED, ctx, metadata);

        recorder.onSituationChange(changeEvent);

        var history = queryService.history("tenant-a", T1, T1.plusSeconds(1));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).metadata()).containsEntry("policyVersion", "v2");
        assertThat(history.get(0).metadata()).containsEntry("score", 42);
    }
}
