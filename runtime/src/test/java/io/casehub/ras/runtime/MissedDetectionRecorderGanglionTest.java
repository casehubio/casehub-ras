package io.casehub.ras.runtime;

import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.MissedDetectionRecord;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.testing.FixedDetectionResult;
import io.casehub.ras.testing.MockGanglion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissedDetectionRecorderGanglionTest {

    private static final FeedbackConfig CONFIG = new FeedbackConfig(
            Set.of("n"), Set.of("c"),
            Duration.ofHours(6), 0.1, Duration.ofDays(90), false);

    @Test
    void rejectsUnknownGanglionId() {
        var recorder = createRecorder();
        var record = new MissedDetectionRecord("sit-1", "ck", "t1",
                Instant.now(), "op", UUID.randomUUID(), Instant.now(),
                List.of("g1", "unknown-ganglion"));

        var result = recorder.record(record);
        assertFalse(result.accepted());
        assertEquals("UNKNOWN_GANGLION", result.rejectionReason());
    }

    @Test
    void acceptsValidGanglionIds() {
        var recorder = createRecorder();
        var record = new MissedDetectionRecord("sit-1", "ck", "t1",
                Instant.now(), "op", UUID.randomUUID(), Instant.now(),
                List.of("g1"));

        var result = recorder.record(record);
        assertTrue(result.accepted());
    }

    @Test
    void nullGanglionIdsSkipsValidation() {
        var recorder = createRecorder();
        var record = new MissedDetectionRecord("sit-1", "ck", "t1",
                Instant.now(), "op", UUID.randomUUID(), Instant.now());

        var result = recorder.record(record);
        assertTrue(result.accepted());
    }

    private MissedDetectionRecorder createRecorder() {
        var ganglion = new MockGanglion("g1", Set.of("test.event"),
                FixedDetectionResult.detected("g1", 0.9));
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null, null, null, Map.of(), CONFIG, null);
        var registry = new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))),
                List.of(ganglion));
        return new MissedDetectionRecorder(new InMemoryOutcomeLedger(), registry);
    }
}
