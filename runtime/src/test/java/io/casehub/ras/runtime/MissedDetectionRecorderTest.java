package io.casehub.ras.runtime;

import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.MissedDetectionRecord;
import io.casehub.ras.api.OutcomeClassification;
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

class MissedDetectionRecorderTest {

    private InMemoryOutcomeLedger ledger;
    private MissedDetectionRecorder recorder;

    private static final FeedbackConfig FEEDBACK_CONFIG = new FeedbackConfig(
            Set.of("dismissed"), Set.of("escalated"),
            Duration.ofMinutes(30), 0.1, Duration.ofDays(30), false);

    @BeforeEach
    void setUp() {
        ledger = new InMemoryOutcomeLedger();
        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                FixedDetectionResult.detected("g1", 0.9));
        var defWithFeedback = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null, null, null, Map.of(), FEEDBACK_CONFIG, null);
        var defNoFeedback = new SituationDefinition("sit-no-feedback", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(defWithFeedback),
                                      new SituationRegistration(defNoFeedback))),
                List.of(ganglion));
        recorder = new MissedDetectionRecorder(ledger, registry);
    }

    private MissedDetectionRecord missed(String situationId, String correlationKey,
                                          String tenancyId, Instant eventTime) {
        return new MissedDetectionRecord(situationId, correlationKey, tenancyId,
                eventTime, "test-operator", UUID.randomUUID(), Instant.now());
    }

    @Test
    void record_stores_valid_missed_detection() {
        var result = recorder.record(missed("sit-1", "key-1", "tenant-a",
                Instant.now().minus(Duration.ofMinutes(5))));
        assertThat(result.accepted()).isTrue();
        assertThat(result.isNew()).isTrue();
    }

    @Test
    void record_rejects_unknown_situation() {
        var result = recorder.record(missed("unknown-sit", "key-1", "tenant-a",
                Instant.now().minus(Duration.ofMinutes(5))));
        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("UNKNOWN_SITUATION");
    }

    @Test
    void record_rejects_situation_without_feedback_config() {
        var result = recorder.record(missed("sit-no-feedback", "key-1", "tenant-a",
                Instant.now().minus(Duration.ofMinutes(5))));
        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("FEEDBACK_NOT_CONFIGURED");
    }

    @Test
    void record_rejects_event_outside_retention_window() {
        var result = recorder.record(missed("sit-1", "key-1", "tenant-a",
                Instant.now().minus(Duration.ofDays(60))));
        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("EVENT_OUTSIDE_WINDOW");
    }

    @Test
    void record_rejects_future_event_beyond_skew_tolerance() {
        var result = recorder.record(missed("sit-1", "key-1", "tenant-a",
                Instant.now().plus(Duration.ofMinutes(5))));
        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("EVENT_OUTSIDE_WINDOW");
    }

    @Test
    void record_returns_not_new_on_duplicate() {
        Instant eventTime = Instant.now().minus(Duration.ofMinutes(5));
        recorder.record(missed("sit-1", "key-1", "tenant-a", eventTime));
        var result = recorder.record(missed("sit-1", "key-1", "tenant-a", eventTime));
        assertThat(result.accepted()).isTrue();
        assertThat(result.isNew()).isFalse();
    }
}
