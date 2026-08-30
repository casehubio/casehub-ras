package io.casehub.ras.runtime;

import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.MissedDetectionRecord;
import io.casehub.ras.api.OutcomeLedger;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationEvent;
import io.casehub.ras.api.SituationQueryService;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MissedDetectionRecorderCrossRefTest {

    private static final FeedbackConfig CONFIG = new FeedbackConfig(
            Set.of("n"), Set.of("c"),
            Duration.ofHours(6), 0.1, Duration.ofDays(90), false);

    @Test
    void possiblyDetected_whenTriggerFoundInHistory() {
        var registry = mockRegistry();
        var ledger = mockLedger();
        Instant eventTime = Instant.now();

        var queryService = mock(SituationQueryService.class);
        when(queryService.history(eq("t1"), eq("sit1"), eq("ck"),
                any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(triggerEvent(eventTime.minusSeconds(60))));

        @SuppressWarnings("unchecked")
        Instance<SituationQueryService> qsInstance = mock(Instance.class);
        when(qsInstance.isResolvable()).thenReturn(true);
        when(qsInstance.get()).thenReturn(queryService);

        var recorder = new MissedDetectionRecorder(ledger, registry, null, qsInstance,
                Duration.ofDays(30));
        var result = recorder.record(missedRecord("sit1", "ck", "t1", eventTime));

        assertTrue(result.accepted());
        assertTrue(result.possiblyDetected());
        assertTrue(result.crossRefConclusive());
        assertNotNull(result.lastTriggerTime());
    }

    @Test
    void notDetected_noTriggerInHistory() {
        var registry = mockRegistry();
        var ledger = mockLedger();

        var queryService = mock(SituationQueryService.class);
        when(queryService.history(any(), any(), any(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Instance<SituationQueryService> qsInstance = mock(Instance.class);
        when(qsInstance.isResolvable()).thenReturn(true);
        when(qsInstance.get()).thenReturn(queryService);

        var recorder = new MissedDetectionRecorder(ledger, registry, null, qsInstance,
                Duration.ofDays(30));
        var result = recorder.record(missedRecord("sit1", "ck", "t1", Instant.now()));

        assertTrue(result.accepted());
        assertFalse(result.possiblyDetected());
        assertTrue(result.crossRefConclusive());
    }

    @Test
    void crossRefNotConclusive_eventOutsideHistoryRetention() {
        var registry = mockRegistry();
        var ledger = mockLedger();

        @SuppressWarnings("unchecked")
        Instance<SituationQueryService> qsInstance = mock(Instance.class);
        when(qsInstance.isResolvable()).thenReturn(true);

        var recorder = new MissedDetectionRecorder(ledger, registry, null, qsInstance,
                Duration.ofDays(30));
        var result = recorder.record(missedRecord("sit1", "ck", "t1",
                Instant.now().minus(Duration.ofDays(60))));

        assertTrue(result.accepted());
        assertFalse(result.possiblyDetected());
        assertFalse(result.crossRefConclusive());
    }

    @Test
    void graceful_whenSituationQueryServiceAbsent() {
        var registry = mockRegistry();
        var ledger = mockLedger();

        @SuppressWarnings("unchecked")
        Instance<SituationQueryService> qsInstance = mock(Instance.class);
        when(qsInstance.isResolvable()).thenReturn(false);

        var recorder = new MissedDetectionRecorder(ledger, registry, null, qsInstance,
                Duration.ofDays(30));
        var result = recorder.record(missedRecord("sit1", "ck", "t1", Instant.now()));

        assertTrue(result.accepted());
        assertFalse(result.possiblyDetected());
        assertFalse(result.crossRefConclusive());
    }

    @Test
    void graceful_whenQueryServiceThrows() {
        var registry = mockRegistry();
        var ledger = mockLedger();

        var queryService = mock(SituationQueryService.class);
        when(queryService.history(any(), any(), any(), any(Instant.class), any(Instant.class)))
                .thenThrow(new RuntimeException("DB timeout"));

        @SuppressWarnings("unchecked")
        Instance<SituationQueryService> qsInstance = mock(Instance.class);
        when(qsInstance.isResolvable()).thenReturn(true);
        when(qsInstance.get()).thenReturn(queryService);

        var recorder = new MissedDetectionRecorder(ledger, registry, null, qsInstance,
                Duration.ofDays(30));
        var result = recorder.record(missedRecord("sit1", "ck", "t1", Instant.now()));

        assertTrue(result.accepted());
        assertFalse(result.possiblyDetected());
        assertFalse(result.crossRefConclusive());
    }

    private SituationDefinitionRegistry mockRegistry() {
        var registry = mock(SituationDefinitionRegistry.class);
        when(registry.exists("sit1")).thenReturn(true);
        when(registry.feedbackConfig("sit1")).thenReturn(CONFIG);
        return registry;
    }

    private OutcomeLedger mockLedger() {
        var ledger = mock(OutcomeLedger.class);
        when(ledger.recordMissed(any())).thenReturn(true);
        return ledger;
    }

    private MissedDetectionRecord missedRecord(String sitId, String ck, String tid, Instant eventTime) {
        return new MissedDetectionRecord(sitId, ck, tid, eventTime,
                "op", UUID.randomUUID(), Instant.now());
    }

    private SituationEvent triggerEvent(Instant eventTime) {
        return new SituationEvent("sit1", "ck", "t1",
                SituationChangeEvent.ChangeType.TRIGGERED, eventTime,
                eventTime, 0.8, 1, 0, Map.of(), Map.of());
    }
}
