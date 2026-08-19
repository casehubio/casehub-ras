package io.casehub.ras.runtime;

import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockGanglion;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SituationReplayRunnerTest {

    private static final CaseTriggerConfig TRIGGER_CONFIG =
            new CaseTriggerConfig("ns", "case", "1.0", Map.of());

    private CloudEvent event(String type, String tenancyId, Instant time) {
        return CloudEventBuilder.v1()
                .withId("evt-" + time.toEpochMilli())
                .withSource(URI.create("/test"))
                .withType(type)
                .withTime(OffsetDateTime.ofInstant(time, ZoneOffset.UTC))
                .withExtension("tenancyid", tenancyId)
                .build();
    }

    private CloudEvent event(String type, String tenancyId) {
        return event(type, tenancyId, Instant.parse("2026-06-25T10:00:00Z"));
    }

    @Test
    void singleGanglionOrModeTriggersCase() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null,
                new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var result = SituationReplayRunner.builder()
                .withRegistrations(List.of(new SituationRegistration(def)))
                .withGanglia(List.of(ganglion))
                .withEvents(List.of(event("temp.reading", "tenant-a")))
                .build()
                .run();

        assertThat(result.didTrigger("sit-1")).isTrue();
        assertThat(result.triggers()).hasSize(1);
        assertThat(result.timeline()).isNotEmpty();
        assertThat(result.summary().totalTriggers()).isEqualTo(1);
        assertThat(result.summary().triggersBySituation()).containsEntry("sit-1", 1);
    }

    @Test
    void andModeAccumulatesUntilAllGangliaFired() {
        var g1 = new MockGanglion("g1", Set.of("temp.reading"),
                                  FixedDetectionResult.detected("g1", 0.9));
        var g2 = new MockGanglion("g2", Set.of("vibration.reading"),
                                  FixedDetectionResult.detected("g2", 0.8));
        var def = new SituationDefinition("sit-1",
                                          Set.of("temp.reading", "vibration.reading"),
                                          Duration.ofMinutes(5), null,
                                          new ChainMode.And(Set.of("g1", "g2")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        Instant t1 = Instant.parse("2026-06-25T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-25T10:01:00Z");

        var result = SituationReplayRunner.builder()
                                          .withRegistrations(List.of(new SituationRegistration(def)))
                                          .withGanglia(List.of(g1, g2))
                                          .withEvents(List.of(
                                                  event("temp.reading", "tenant-a", t1),
                                                  event("vibration.reading", "tenant-a", t2)))
                                          .build()
                                          .run();

        assertThat(result.didTrigger("sit-1")).isTrue();
        assertThat(result.triggers()).hasSize(1);
    }

    @Test
    void thresholdModeTriggersWhenConfidenceSumReached() {
        var g1 = new MockGanglion("g1", Set.of("sensor.reading"),
                                  FixedDetectionResult.detected("g1", 0.5));
        var g2 = new MockGanglion("g2", Set.of("sensor.reading"),
                                  FixedDetectionResult.detected("g2", 0.4));
        var def = new SituationDefinition("sit-1", Set.of("sensor.reading"),
                                          Duration.ofMinutes(5), null,
                                          new ChainMode.Threshold(Set.of("g1", "g2"), 0.8),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var result = SituationReplayRunner.builder()
                                          .withRegistrations(List.of(new SituationRegistration(def)))
                                          .withGanglia(List.of(g1, g2))
                                          .withEvents(List.of(event("sensor.reading", "tenant-a")))
                                          .build()
                                          .run();

        assertThat(result.didTrigger("sit-1")).isTrue();
    }

    @Test
    void nonTriggeringReplayShowsFinalStateWithDetections() {
        var ganglion = new MockGanglion("g1", Set.of("sensor.reading"),
                                        FixedDetectionResult.detected("g1", 0.3));
        var def = new SituationDefinition("sit-1", Set.of("sensor.reading"),
                                          Duration.ofMinutes(5), null,
                                          new ChainMode.Threshold(Set.of("g1"), 0.8),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var result = SituationReplayRunner.builder()
                                          .withRegistrations(List.of(new SituationRegistration(def)))
                                          .withGanglia(List.of(ganglion))
                                          .withEvents(List.of(event("sensor.reading", "tenant-a")))
                                          .build()
                                          .run();

        assertThat(result.didTrigger("sit-1")).isFalse();
        assertThat(result.finalState()).isNotEmpty();
        var entry = result.finalState().values().iterator().next();
        assertThat(entry.detections()).isNotEmpty();
    }

    @Test
    void differentTenantsAreIsolated() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null,
                                          new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var result = SituationReplayRunner.builder()
                                          .withRegistrations(List.of(new SituationRegistration(def)))
                                          .withGanglia(List.of(ganglion))
                                          .withEvents(List.of(
                                                  event("temp.reading", "tenant-a"),
                                                  event("temp.reading", "tenant-b")))
                                          .build()
                                          .run();

        assertThat(result.triggers()).hasSize(2);
        assertThat(result.summary().triggersByTenancy()).containsEntry("tenant-a", 1);
        assertThat(result.summary().triggersByTenancy()).containsEntry("tenant-b", 1);
    }

    @Test
    void unmatchedEventsAreSilentlySkipped() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null,
                                          new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        var result = SituationReplayRunner.builder()
                                          .withRegistrations(List.of(new SituationRegistration(def)))
                                          .withGanglia(List.of(ganglion))
                                          .withEvents(List.of(
                                                  event("unrelated.event", "tenant-a"),
                                                  event("temp.reading", "tenant-a")))
                                          .build()
                                          .run();

        assertThat(result.triggers()).hasSize(1);
        assertThat(result.skippedEvents()).isEmpty();
        assertThat(result.summary().eventsProcessed()).isEqualTo(2);
    }

    @Test
    void strictModeThrowsOnMissingTenancyId() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null,
                                          new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        CloudEvent noTenancy = CloudEventBuilder.v1()
                                                .withId("evt-1").withSource(java.net.URI.create("/test")).withType("temp.reading")
                                                .withTime(java.time.OffsetDateTime.ofInstant(Instant.parse("2026-06-25T10:00:00Z"), java.time.ZoneOffset.UTC))
                                                .build();

        var runner = SituationReplayRunner.builder()
                                          .withRegistrations(List.of(new SituationRegistration(def)))
                                          .withGanglia(List.of(ganglion))
                                          .withEvents(List.of(noTenancy))
                                          .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(runner::run)
                                       .isInstanceOf(IllegalArgumentException.class)
                                       .hasMessageContaining("tenancyid");
    }

    @Test
    void lenientModeSkipsMissingTenancyId() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null,
                                          new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);
        CloudEvent noTenancy = CloudEventBuilder.v1()
                                                .withId("evt-1").withSource(java.net.URI.create("/test")).withType("temp.reading")
                                                .withTime(java.time.OffsetDateTime.ofInstant(Instant.parse("2026-06-25T10:00:00Z"), java.time.ZoneOffset.UTC))
                                                .build();

        var result = SituationReplayRunner.builder()
                                          .withRegistrations(List.of(new SituationRegistration(def)))
                                          .withGanglia(List.of(ganglion))
                                          .withEvents(List.of(noTenancy, event("temp.reading", "tenant-a")))
                                          .withErrorHandling(ReplayErrorHandling.LENIENT)
                                          .build()
                                          .run();

        assertThat(result.skippedEvents()).hasSize(1);
        assertThat(result.skippedEvents().get(0).reason()).contains("tenancyid");
        assertThat(result.triggers()).hasSize(1);
    }

    @Test
    void eventReorderBufferHandlesOutOfOrderEvents() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), Duration.ofSeconds(5),
                                          new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        Instant t1 = Instant.parse("2026-06-25T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-25T10:00:10Z");

        var result = SituationReplayRunner.builder()
                                          .withRegistrations(List.of(new SituationRegistration(def)))
                                          .withGanglia(List.of(ganglion))
                                          .withEvents(List.of(
                                                  event("temp.reading", "tenant-a", t2),
                                                  event("temp.reading", "tenant-a", t1)))
                                          .build()
                                          .run();

        assertThat(result.didTrigger("sit-1")).isTrue();
    }

    @Test
    void emptyEventStreamIsRejectedByBuilder() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("temp.reading"),
                                          Duration.ofMinutes(5), null,
                                          new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(TRIGGER_CONFIG), null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                                                                   SituationReplayRunner.builder()
                                                                                        .withRegistrations(List.of(new SituationRegistration(def)))
                                                                                        .withGanglia(List.of(ganglion))
                                                                                        .withEvents(List.of())
                                                                                        .build())
                                       .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void yamlDefinitionLoadingProducesSameResultsAsProgrammatic() {
        var ganglion = new MockGanglion("g1", Set.of("temp.reading"),
                                        FixedDetectionResult.detected("g1", 0.9));

        var result = SituationReplayRunner.builder()
                                          .withYaml("META-INF/test-replay-situations.yaml")
                                          .withGanglia(List.of(ganglion))
                                          .withEvents(List.of(event("temp.reading", "tenant-a")))
                                          .build()
                                          .run();

        assertThat(result.didTrigger("yaml-sit-1")).isTrue();
        assertThat(result.triggers()).hasSize(1);
    }


}
