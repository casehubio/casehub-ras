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
}
