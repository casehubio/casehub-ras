package io.casehub.ras.runtime;

import io.casehub.api.spi.CaseOutcomeEvent;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.OutcomeLedger;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.TriggerAction;
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

class OutcomeRecorderTest {

    private InMemoryOutcomeLedger ledger;
    private SituationDefinitionRegistry registry;

    private FeedbackConfig feedbackConfig() {
        return new FeedbackConfig(
                Set.of("dismissed", "false-positive"), Set.of("escalated"),
                Duration.ofHours(6), 0.1, Duration.ofDays(90), false);
    }

    @BeforeEach
    void setUp() {
        ledger = new InMemoryOutcomeLedger();
    }

    private SituationDefinitionRegistry registryWith(SituationDefinition def, String... ganglionEvents) {
        var ganglion = new MockGanglion("g1", Set.of(ganglionEvents),
                io.casehub.ras.testing.FixedDetectionResult.detected("g1", 0.9));
        return new SituationDefinitionRegistry(
                List.of(() -> List.of(new SituationRegistration(def))),
                List.of(ganglion));
    }

    private CaseOutcomeEvent outcomeEvent(String outcomeLabel, Map<String, Object> snapshot) {
        return new CaseOutcomeEvent("ns:case:1.0", "tenant-a", UUID.randomUUID(),
                snapshot, outcomeLabel, Instant.now(), Map.of());
    }

    @Test
    void recordsOutcomeWithCorrectClassification() {
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null, null, null, Map.of(), feedbackConfig(), null);
        registry = registryWith(def, "test.event");

        var recorder = new OutcomeRecorder(ledger, registry);
        recorder.onOutcome(outcomeEvent("dismissed", Map.of(
                "situationId", "sit-1", "correlationKey", "key-1", "tenancyId", "tenant-a")));

        var stats = ledger.statistics("sit-1", "tenant-a", Instant.EPOCH);
        assertThat(stats.totalOutcomes()).isEqualTo(1);
        assertThat(stats.noiseCount()).isEqualTo(1);
    }

    @Test
    void skipsWhenNoSituationIdInSnapshot() {
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null, null, null, Map.of(), feedbackConfig(), null);
        registry = registryWith(def, "test.event");

        var recorder = new OutcomeRecorder(ledger, registry);
        recorder.onOutcome(outcomeEvent("dismissed", Map.of()));

        var stats = ledger.statistics("sit-1", "tenant-a", Instant.EPOCH);
        assertThat(stats.totalOutcomes()).isZero();
    }

    @Test
    void skipsWhenNoFeedbackConfig() {
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null);
        registry = registryWith(def, "test.event");

        var recorder = new OutcomeRecorder(ledger, registry);
        recorder.onOutcome(outcomeEvent("dismissed", Map.of(
                "situationId", "sit-1", "correlationKey", "key-1", "tenancyId", "tenant-a")));

        var stats = ledger.statistics("sit-1", "tenant-a", Instant.EPOCH);
        assertThat(stats.totalOutcomes()).isZero();
    }

    @Test
    void skipsWhenCorrelationKeyNull() {
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null, null, null, Map.of(), feedbackConfig(), null);
        registry = registryWith(def, "test.event");

        var recorder = new OutcomeRecorder(ledger, registry);
        recorder.onOutcome(outcomeEvent("dismissed", Map.of(
                "situationId", "sit-1", "tenancyId", "tenant-a")));

        var stats = ledger.statistics("sit-1", "tenant-a", Instant.EPOCH);
        assertThat(stats.totalOutcomes()).isZero();
    }

    @Test
    void swallowsLedgerExceptions() {
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null, null, null, Map.of(), feedbackConfig(), null);
        registry = registryWith(def, "test.event");

        OutcomeLedger failingLedger = new OutcomeLedger() {
            public void record(io.casehub.ras.api.OutcomeRecord r) { throw new RuntimeException("boom"); }
            public io.casehub.ras.api.OutcomeStatistics statistics(String s, String t, Instant i) { return null; }
            public java.util.Optional<Instant> lastNoiseDismissalTime(String s, String c, String t) { return java.util.Optional.empty(); }
            public Map<String, Long> countByLabel(String s, String t, Instant i) { return Map.of(); }
            public Set<String> distinctTenancies(String s) { return Set.of(); }
            public int removeRecordsBefore(String s, Instant c) { return 0; }
            public java.util.Map<String, io.casehub.ras.api.GanglionOutcomeStatistics> ganglionStatistics(String s, String t, Instant i) { return java.util.Map.of(); }
            public boolean recordMissed(io.casehub.ras.api.MissedDetectionRecord r) { return false; }
        };

        var recorder = new OutcomeRecorder(failingLedger, registry);
        recorder.onOutcome(outcomeEvent("dismissed", Map.of(
                "situationId", "sit-1", "correlationKey", "key-1", "tenancyId", "tenant-a")));
        // no exception thrown — swallowed
    }

    @Test
    void classifiesConfirmedCorrectly() {
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null, null, null, Map.of(), feedbackConfig(), null);
        registry = registryWith(def, "test.event");

        var recorder = new OutcomeRecorder(ledger, registry);
        recorder.onOutcome(outcomeEvent("escalated", Map.of(
                "situationId", "sit-1", "correlationKey", "key-1", "tenancyId", "tenant-a")));

        var stats = ledger.statistics("sit-1", "tenant-a", Instant.EPOCH);
        assertThat(stats.confirmedCount()).isEqualTo(1);
        assertThat(stats.noiseCount()).isZero();
    }

    @Test
    void skipsUnknownSituationId() {
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                null, null, null, Map.of(), feedbackConfig(), null);
        registry = registryWith(def, "test.event");

        var recorder = new OutcomeRecorder(ledger, registry);
        recorder.onOutcome(outcomeEvent("dismissed", Map.of(
                "situationId", "unknown-sit", "correlationKey", "key-1", "tenancyId", "tenant-a")));

        var stats = ledger.statistics("unknown-sit", "tenant-a", Instant.EPOCH);
        assertThat(stats.totalOutcomes()).isZero();
    }

    @Test
    void extractsGanglionContributionsFromSnapshot() {
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                                          null, null, null, Map.of(), feedbackConfig(), null);
        registry = registryWith(def, "test.event");

        var snapshot = new java.util.HashMap<String, Object>();
        snapshot.put("situationId", "sit-1");
        snapshot.put("correlationKey", "key-1");
        snapshot.put("tenancyId", "tenant-a");
        snapshot.put("detections", List.of(
                Map.of("result", Map.of("ganglionId", "g1",
                                        "confidence", 0.8, "signal", "DETECTED"),
                       "eventTime", "2026-01-01T00:00:00Z")));

        var recorder = new OutcomeRecorder(ledger, registry);
        recorder.onOutcome(outcomeEvent("dismissed", snapshot));

        var ganglionStats = ledger.ganglionStatistics("sit-1", "tenant-a", Instant.EPOCH);
        assertThat(ganglionStats).hasSize(1);
        assertThat(ganglionStats.get("g1").noiseCount()).isEqualTo(1);
    }

    @Test
    void deduplicatesByGanglionIdKeepingHighestSignal() {
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                                          null, null, null, Map.of(), feedbackConfig(), null);
        registry = registryWith(def, "test.event");

        var snapshot = new java.util.HashMap<String, Object>();
        snapshot.put("situationId", "sit-1");
        snapshot.put("correlationKey", "key-1");
        snapshot.put("tenancyId", "tenant-a");
        snapshot.put("detections", List.of(
                Map.of("result", Map.of("ganglionId", "g1",
                                        "confidence", 0.3, "signal", "WEAK"),
                       "eventTime", "2026-01-01T00:00:00Z"),
                Map.of("result", Map.of("ganglionId", "g1",
                                        "confidence", 0.9, "signal", "DETECTED"),
                       "eventTime", "2026-01-01T00:01:00Z")));

        var recorder = new OutcomeRecorder(ledger, registry);
        recorder.onOutcome(outcomeEvent("escalated", snapshot));

        var ganglionStats = ledger.ganglionStatistics("sit-1", "tenant-a", Instant.EPOCH);
        assertThat(ganglionStats).hasSize(1);
        assertThat(ganglionStats.get("g1").confirmedCount()).isEqualTo(1);
    }

    @Test
    void deduplicatesByConfidenceWhenSignalsTied() {
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                                          null, null, null, Map.of(), feedbackConfig(), null);
        registry = registryWith(def, "test.event");

        var snapshot = new java.util.HashMap<String, Object>();
        snapshot.put("situationId", "sit-1");
        snapshot.put("correlationKey", "key-1");
        snapshot.put("tenancyId", "tenant-a");
        snapshot.put("detections", List.of(
                Map.of("result", Map.of("ganglionId", "g1",
                                        "confidence", 0.3, "signal", "DETECTED"),
                       "eventTime", "2026-01-01T00:00:00Z"),
                Map.of("result", Map.of("ganglionId", "g1",
                                        "confidence", 0.9, "signal", "DETECTED"),
                       "eventTime", "2026-01-01T00:01:00Z")));

        var recorder = new OutcomeRecorder(ledger, registry);
        recorder.onOutcome(outcomeEvent("escalated", snapshot));

        var ganglionStats = ledger.ganglionStatistics("sit-1", "tenant-a", Instant.EPOCH);
        assertThat(ganglionStats).hasSize(1);
        assertThat(ganglionStats.get("g1").confirmedCount()).isEqualTo(1);
    }


    @Test
    void missingDetectionsRecordsOutcomeWithoutContributions() {
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                                          null, null, null, Map.of(), feedbackConfig(), null);
        registry = registryWith(def, "test.event");

        var recorder = new OutcomeRecorder(ledger, registry);
        recorder.onOutcome(outcomeEvent("dismissed", Map.of(
                "situationId", "sit-1", "correlationKey", "key-1", "tenancyId", "tenant-a")));

        assertThat(ledger.statistics("sit-1", "tenant-a", Instant.EPOCH).totalOutcomes()).isEqualTo(1);
        assertThat(ledger.ganglionStatistics("sit-1", "tenant-a", Instant.EPOCH)).isEmpty();
    }

    @Test
    void malformedDetectionsRecordsOutcomeGracefully() {
        var def = new SituationDefinition("sit-1", Set.of("test.event"),
                                          Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                                          new TriggerAction.CreateCase(new CaseTriggerConfig("ns", "case", "1.0", Map.of())),
                                          null, null, null, Map.of(), feedbackConfig(), null);
        registry = registryWith(def, "test.event");

        var snapshot = new java.util.HashMap<String, Object>();
        snapshot.put("situationId", "sit-1");
        snapshot.put("correlationKey", "key-1");
        snapshot.put("tenancyId", "tenant-a");
        snapshot.put("detections", "not-a-list");

        var recorder = new OutcomeRecorder(ledger, registry);
        recorder.onOutcome(outcomeEvent("dismissed", snapshot));

        assertThat(ledger.statistics("sit-1", "tenant-a", Instant.EPOCH).totalOutcomes()).isEqualTo(1);
        assertThat(ledger.ganglionStatistics("sit-1", "tenant-a", Instant.EPOCH)).isEmpty();
    }

}
