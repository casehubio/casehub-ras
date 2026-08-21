package io.casehub.ras.runtime;

import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.OutcomeClassification;
import io.casehub.ras.api.OutcomeRecord;
import io.casehub.ras.api.OutcomeStatistics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackAnalyzerTest {

    private FeedbackConfig config() {
        return new FeedbackConfig(
                Set.of("dismissed"), Set.of("escalated"),
                Duration.ofHours(6), 0.1, Duration.ofDays(90), false);
    }

    @Test
    void returnsStatisticsWithinRetentionWindow() {
        var ledger = new InMemoryOutcomeLedger();
        ledger.record(new OutcomeRecord("sit-1", "k1", "t1", "dismissed",
                OutcomeClassification.NOISE, Instant.now().minus(Duration.ofDays(10)), UUID.randomUUID()));
        ledger.record(new OutcomeRecord("sit-1", "k1", "t1", "escalated",
                OutcomeClassification.CONFIRMED, Instant.now().minus(Duration.ofDays(5)), UUID.randomUUID()));

        var analyzer = new FeedbackAnalyzer(ledger);
        OutcomeStatistics stats = analyzer.analyze("sit-1", "t1", config());

        assertThat(stats.totalOutcomes()).isEqualTo(2);
        assertThat(stats.noiseCount()).isEqualTo(1);
        assertThat(stats.confirmedCount()).isEqualTo(1);
    }

    @Test
    void excludesRecordsOutsideRetentionWindow() {
        var ledger = new InMemoryOutcomeLedger();
        ledger.record(new OutcomeRecord("sit-1", "k1", "t1", "dismissed",
                OutcomeClassification.NOISE, Instant.now().minus(Duration.ofDays(100)), UUID.randomUUID()));
        ledger.record(new OutcomeRecord("sit-1", "k1", "t1", "escalated",
                OutcomeClassification.CONFIRMED, Instant.now().minus(Duration.ofDays(5)), UUID.randomUUID()));

        var analyzer = new FeedbackAnalyzer(ledger);
        OutcomeStatistics stats = analyzer.analyze("sit-1", "t1", config());

        assertThat(stats.totalOutcomes()).isEqualTo(1);
        assertThat(stats.confirmedCount()).isEqualTo(1);
        assertThat(stats.noiseCount()).isZero();
    }

    @Test
    void emptyWhenNoRecords() {
        var ledger = new InMemoryOutcomeLedger();
        var analyzer = new FeedbackAnalyzer(ledger);
        OutcomeStatistics stats = analyzer.analyze("sit-1", "t1", config());

        assertThat(stats.totalOutcomes()).isZero();
    }

    @Test
    void ganglionAnalyzeAppliesRetentionWindow() {
        var ledger   = new InMemoryOutcomeLedger();
        var analyzer = new FeedbackAnalyzer(ledger);

        Instant old    = Instant.now().minus(Duration.ofDays(100));
        Instant recent = Instant.now().minusSeconds(60);

        ledger.record(new OutcomeRecord("sit-1", "k1", "t1", "dismissed",
                                        OutcomeClassification.NOISE, old, UUID.randomUUID(),
                                        java.util.List.of(new io.casehub.ras.api.GanglionContribution("g1", 0.8,
                                                                                                      io.casehub.ras.api.DetectionSignal.DETECTED))));
        ledger.record(new OutcomeRecord("sit-1", "k2", "t1", "escalated",
                                        OutcomeClassification.CONFIRMED, recent, UUID.randomUUID(),
                                        java.util.List.of(new io.casehub.ras.api.GanglionContribution("g1", 0.9,
                                                                                                      io.casehub.ras.api.DetectionSignal.DETECTED))));

        var stats = analyzer.ganglionAnalyze("sit-1", "t1", config());

        assertThat(stats.get("g1").totalOutcomes()).isEqualTo(1);
        assertThat(stats.get("g1").confirmedCount()).isEqualTo(1);
        assertThat(stats.get("g1").noiseCount()).isZero();
    }

}
