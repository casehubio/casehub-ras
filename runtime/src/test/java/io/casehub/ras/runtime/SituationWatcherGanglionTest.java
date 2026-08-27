package io.casehub.ras.runtime;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SituationWatcherGanglionTest {

    @Test
    void detect_maps_triggered_to_detected() {
        var mapping = Map.of(SituationChangeEvent.ChangeType.TRIGGERED, DetectionSignal.DETECTED);
        var ganglion = new SituationWatcherGanglion("watcher", mapping);
        CloudEvent event = bridgedEvent("sit-1", "key-1", "tenant-1", "TRIGGERED");
        var ctx = SituationContext.initial("meta", "key", "t", Instant.now());
        DetectionResult result = ganglion.detect(event, ctx);
        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.ganglionId()).isEqualTo("watcher");
    }

    @Test
    void detect_maps_resolved_to_anti() {
        var mapping = Map.of(
                SituationChangeEvent.ChangeType.TRIGGERED, DetectionSignal.DETECTED,
                SituationChangeEvent.ChangeType.RESOLVED, DetectionSignal.ANTI);
        var ganglion = new SituationWatcherGanglion("watcher", mapping);
        CloudEvent event = bridgedEvent("sit-1", "key-1", "tenant-1", "RESOLVED");
        var ctx = SituationContext.initial("meta", "key", "t", Instant.now());
        DetectionResult result = ganglion.detect(event, ctx);
        assertThat(result.signal()).isEqualTo(DetectionSignal.ANTI);
    }

    @Test
    void detect_returns_noise_for_missing_extension() {
        var mapping = Map.of(SituationChangeEvent.ChangeType.TRIGGERED, DetectionSignal.DETECTED);
        var ganglion = new SituationWatcherGanglion("watcher", mapping);
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1").withType("ras.situation.triggered")
                .withSource(URI.create("ras://bridge")).build();
        var ctx = SituationContext.initial("meta", "key", "t", Instant.now());
        DetectionResult result = ganglion.detect(event, ctx);
        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void detect_includes_automatic_evidence() {
        var mapping = Map.of(SituationChangeEvent.ChangeType.TRIGGERED, DetectionSignal.DETECTED);
        var ganglion = new SituationWatcherGanglion("watcher", mapping);
        CloudEvent event = bridgedEvent("child-sit", "child-key", "tenant-1", "TRIGGERED");
        var ctx = SituationContext.initial("meta", "key", "t", Instant.now());
        DetectionResult result = ganglion.detect(event, ctx);
        assertThat(result.evidence()).containsEntry("childSituationId", "child-sit");
        assertThat(result.evidence()).containsEntry("childCorrelationKey", "child-key");
        assertThat(result.evidence()).containsEntry("childChangeType", "TRIGGERED");
    }

    @Test
    void handledEventTypes_derived_from_mapping() {
        var mapping = Map.of(
                SituationChangeEvent.ChangeType.TRIGGERED, DetectionSignal.DETECTED,
                SituationChangeEvent.ChangeType.RESOLVED, DetectionSignal.ANTI);
        var ganglion = new SituationWatcherGanglion("watcher", mapping);
        assertThat(ganglion.handledEventTypes()).containsExactlyInAnyOrder(
                "ras.situation.triggered", "ras.situation.resolved");
    }

    @Test
    void handledEventTypes_single_mapping() {
        var mapping = Map.of(SituationChangeEvent.ChangeType.TRIGGERED, DetectionSignal.DETECTED);
        var ganglion = new SituationWatcherGanglion("watcher", mapping);
        assertThat(ganglion.handledEventTypes()).containsExactly("ras.situation.triggered");
    }

    @Test
    void ganglionId_returned() {
        var mapping = Map.of(SituationChangeEvent.ChangeType.TRIGGERED, DetectionSignal.DETECTED);
        var ganglion = new SituationWatcherGanglion("my-watcher", mapping);
        assertThat(ganglion.ganglionId()).isEqualTo("my-watcher");
    }

    private static CloudEvent bridgedEvent(String situationId, String correlationKey,
                                            String tenancyId, String changeType) {
        return CloudEventBuilder.v1()
                .withId("1")
                .withType("ras.situation." + changeType.toLowerCase())
                .withSource(URI.create("ras://bridge"))
                .withSubject(situationId)
                .withExtension("tenancyid", tenancyId)
                .withExtension("situationid", situationId)
                .withExtension("correlationkey", correlationKey)
                .withExtension("changetype", changeType)
                .build();
    }
}
