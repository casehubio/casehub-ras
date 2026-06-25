package io.casehub.ras.api;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class SituationContextTest {

    private static final Instant T1 = Instant.parse("2026-06-20T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-20T10:05:00Z");
    private static final Instant T0 = Instant.parse("2026-06-20T09:55:00Z");

    private static final DetectionResult RESULT_A = new DetectionResult(
            "temp-ganglion", 0.8, DetectionSignal.DETECTED, Map.of("temp", 95.0));
    private static final DetectionResult RESULT_B = new DetectionResult(
            "vibration-ganglion", 0.6, DetectionSignal.WEAK, Map.of("freq", 120));

    @Test
    void initialCreatesContextWithEmptyDetections() {
        var ctx = SituationContext.initial("sit-1", "machine-42", "tenant-a", T1);

        assertThat(ctx.situationId()).isEqualTo("sit-1");
        assertThat(ctx.correlationKey()).isEqualTo("machine-42");
        assertThat(ctx.tenancyId()).isEqualTo("tenant-a");
        assertThat(ctx.firstSignal()).isEqualTo(T1);
        assertThat(ctx.lastSignal()).isEqualTo(T1);
        assertThat(ctx.detections()).isEmpty();
    }

    @Test
    void withDetectionAppendsTimestampedAndUpdatesLastSignal() {
        var ctx = SituationContext.initial("sit-1", "machine-42", "tenant-a", T1)
                .withDetection(RESULT_A, T2);

        assertThat(ctx.detections()).hasSize(1);
        assertThat(ctx.detections().getFirst().result()).isEqualTo(RESULT_A);
        assertThat(ctx.detections().getFirst().eventTime()).isEqualTo(T2);
        assertThat(ctx.firstSignal()).isEqualTo(T1);
        assertThat(ctx.lastSignal()).isEqualTo(T2);
    }

    @Test
    void withDetectionHandlesOutOfOrderEarlierEvent() {
        var ctx = SituationContext.initial("sit-1", "machine-42", "tenant-a", T1)
                .withDetection(RESULT_A, T0);

        assertThat(ctx.firstSignal()).isEqualTo(T0);
        assertThat(ctx.lastSignal()).isEqualTo(T1);
    }

    @Test
    void withDetectionHandlesOutOfOrderLaterEvent() {
        var ctx = SituationContext.initial("sit-1", "machine-42", "tenant-a", T1)
                .withDetection(RESULT_A, T2)
                .withDetection(RESULT_B, T1);

        assertThat(ctx.firstSignal()).isEqualTo(T1);
        assertThat(ctx.lastSignal()).isEqualTo(T2);
        assertThat(ctx.detections()).hasSize(2);
        assertThat(ctx.detections().get(0).result()).isEqualTo(RESULT_A);
        assertThat(ctx.detections().get(1).result()).isEqualTo(RESULT_B);
    }

    @Test
    void withDetectionIsImmutable() {
        var original = SituationContext.initial("sit-1", "machine-42", "tenant-a", T1);
        var updated = original.withDetection(RESULT_A, T2);

        assertThat(original.detections()).isEmpty();
        assertThat(updated.detections()).hasSize(1);
    }

    @Test
    void nullSituationIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> SituationContext.initial(null, "key", "tenant-a", T1))
                .withMessage("situationId");
    }

    @Test
    void nullCorrelationKeyIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> SituationContext.initial("sit-1", null, "tenant-a", T1))
                .withMessage("correlationKey");
    }

    @Test
    void nullTenancyIdIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> SituationContext.initial("sit-1", "key", null, T1))
                .withMessage("tenancyId");
    }

    @Test
    void detectionsAreDefensivelyCopied() {
        var td = new TimestampedDetection(RESULT_A, T1);
        var mutableDetections = new ArrayList<>(List.of(td));
        var ctx = new SituationContext("sit-1", "key", "tenant-a", T1, T1, mutableDetections);
        mutableDetections.add(new TimestampedDetection(RESULT_B, T2));
        assertThat(ctx.detections()).hasSize(1);
    }

    @Test
    void nullDetectionsNormalisedToEmptyList() {
        var ctx = new SituationContext("sit-1", "key", "tenant-a", T1, T1, null);
        assertThat(ctx.detections()).isNotNull().isEmpty();
    }
}
