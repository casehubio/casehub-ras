package io.casehub.ras.runtime;

import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockGanglion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MissedDetectionResourceTest {

    private MissedDetectionResource resource;
    private InMemoryOutcomeLedger ledger;

    private static final FeedbackConfig FEEDBACK_CONFIG = new FeedbackConfig(
            Set.of("dismissed"), Set.of("escalated"),
            Duration.ofMinutes(30), 0.1, Duration.ofDays(30), false);

    @BeforeEach
    void setUp() {
        ledger = new InMemoryOutcomeLedger();
        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null, null, null, Map.of(), FEEDBACK_CONFIG, null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))),
                List.of(ganglion));
        var recorder = new MissedDetectionRecorder(ledger, registry);
        resource = new MissedDetectionResource(recorder);
    }

    @Test
    void post_valid_missed_detection_returns_201() {
        var result = resource.reportMissed(new MissedDetectionResource.MissedDetectionRequest(
                "sit-1", "key-1", "tenant-a",
                Instant.now().minus(Duration.ofMinutes(5)),
                "operator@example.com",
                UUID.randomUUID()));
        assertThat(result.getStatus()).isEqualTo(201);
    }

    @Test
    void post_duplicate_returns_200() {
        Instant eventTime = Instant.now().minus(Duration.ofMinutes(5));
        resource.reportMissed(new MissedDetectionResource.MissedDetectionRequest(
                "sit-1", "key-1", "tenant-a", eventTime, "op", UUID.randomUUID()));
        var result = resource.reportMissed(new MissedDetectionResource.MissedDetectionRequest(
                "sit-1", "key-1", "tenant-a", eventTime, "op", UUID.randomUUID()));
        assertThat(result.getStatus()).isEqualTo(200);
    }

    @Test
    void post_unknown_situation_returns_400() {
        var result = resource.reportMissed(new MissedDetectionResource.MissedDetectionRequest(
                "unknown", "key-1", "tenant-a",
                Instant.now().minus(Duration.ofMinutes(5)),
                "op", UUID.randomUUID()));
        assertThat(result.getStatus()).isEqualTo(400);
    }

    @Test
    void post_outside_window_returns_400() {
        var result = resource.reportMissed(new MissedDetectionResource.MissedDetectionRequest(
                "sit-1", "key-1", "tenant-a",
                Instant.now().minus(Duration.ofDays(60)),
                "op", UUID.randomUUID()));
        assertThat(result.getStatus()).isEqualTo(400);
    }

    @Test
    void stored_record_visible_in_statistics() {
        resource.reportMissed(new MissedDetectionResource.MissedDetectionRequest(
                "sit-1", "key-1", "tenant-a",
                Instant.now().minus(Duration.ofMinutes(5)),
                "op", UUID.randomUUID()));
        var stats = ledger.statistics("sit-1", "tenant-a", Instant.now().minus(Duration.ofHours(1)));
        assertThat(stats.missedCount()).isEqualTo(1);
    }
}
