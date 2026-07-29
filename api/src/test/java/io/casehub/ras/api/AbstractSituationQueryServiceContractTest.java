package io.casehub.ras.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractSituationQueryServiceContractTest {

    protected SituationQueryService queryService;
    protected SituationEventRetention retention;

    protected static final Instant T1 = Instant.parse("2026-06-20T10:00:00Z");
    protected static final Instant T2 = Instant.parse("2026-06-20T11:00:00Z");
    protected static final Instant T3 = Instant.parse("2026-06-20T12:00:00Z");
    protected static final Instant T4 = Instant.parse("2026-06-20T13:00:00Z");
    protected static final Instant T5 = Instant.parse("2026-06-20T14:00:00Z");

    protected abstract SituationQueryService createQueryService();

    protected abstract SituationEventRetention createRetention();

    protected abstract void seed(SituationEvent event);

    @BeforeEach
    void setUp() {
        queryService = createQueryService();
        retention = createRetention();
    }

    // --- helpers ---

    protected SituationEvent event(String sitId, String corrKey, String tenant,
                                    SituationChangeEvent.ChangeType changeType,
                                    Instant eventTime, Instant firstSeen) {
        return new SituationEvent(sitId, corrKey, tenant, changeType, eventTime, firstSeen,
                0.8, 3, 1, Map.of(), Map.of());
    }

    protected SituationEvent triggered(String sitId, String corrKey, String tenant,
                                        Instant eventTime, Instant firstSeen) {
        return event(sitId, corrKey, tenant, SituationChangeEvent.ChangeType.TRIGGERED,
                eventTime, firstSeen);
    }

    protected SituationEvent resolved(String sitId, String corrKey, String tenant,
                                       Instant eventTime, Instant firstSeen) {
        return event(sitId, corrKey, tenant, SituationChangeEvent.ChangeType.RESOLVED,
                eventTime, firstSeen);
    }

    // === history() ===

    @Test
    void historyEmptyWhenNoEvents() {
        assertThat(queryService.history("tenant-a", T1, T5)).isEmpty();
    }

    @Test
    void historyReturnsSingleEvent() {
        seed(triggered("sit-1", "key-1", "tenant-a", T2, T1));
        var result = queryService.history("tenant-a", T1, T5);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).situationId()).isEqualTo("sit-1");
        assertThat(result.get(0).correlationKey()).isEqualTo("key-1");
        assertThat(result.get(0).changeType()).isEqualTo(SituationChangeEvent.ChangeType.TRIGGERED);
    }

    @Test
    void historyFiltersByTenancy() {
        seed(triggered("sit-1", "key-1", "tenant-a", T2, T1));
        seed(triggered("sit-1", "key-1", "tenant-b", T3, T1));
        assertThat(queryService.history("tenant-a", T1, T5)).hasSize(1);
        assertThat(queryService.history("tenant-b", T1, T5)).hasSize(1);
        assertThat(queryService.history("tenant-c", T1, T5)).isEmpty();
    }

    @Test
    void historyFiltersBySituationId() {
        seed(triggered("sit-1", "key-1", "tenant-a", T2, T1));
        seed(triggered("sit-2", "key-1", "tenant-a", T3, T1));
        assertThat(queryService.history("tenant-a", "sit-1", T1, T5)).hasSize(1);
        assertThat(queryService.history("tenant-a", "sit-2", T1, T5)).hasSize(1);
        assertThat(queryService.history("tenant-a", "sit-3", T1, T5)).isEmpty();
    }

    @Test
    void historyFiltersByCorrelationKey() {
        seed(triggered("sit-1", "key-1", "tenant-a", T2, T1));
        seed(triggered("sit-1", "key-2", "tenant-a", T3, T1));
        assertThat(queryService.history("tenant-a", "sit-1", "key-1", T1, T5)).hasSize(1);
        assertThat(queryService.history("tenant-a", "sit-1", "key-2", T1, T5)).hasSize(1);
        assertThat(queryService.history("tenant-a", "sit-1", "key-3", T1, T5)).isEmpty();
    }

    @Test
    void historyFiltersByTimeRange() {
        seed(triggered("sit-1", "key-1", "tenant-a", T1, T1));
        seed(triggered("sit-1", "key-2", "tenant-a", T3, T1));
        seed(triggered("sit-1", "key-3", "tenant-a", T5, T1));
        // [T2, T4) should include T3 only
        assertThat(queryService.history("tenant-a", T2, T4)).hasSize(1);
        assertThat(queryService.history("tenant-a", T2, T4).get(0).correlationKey()).isEqualTo("key-2");
    }

    @Test
    void historyExcludesEventsAtToInstant() {
        seed(triggered("sit-1", "key-1", "tenant-a", T3, T1));
        // [T1, T3) — T3 is exclusive
        assertThat(queryService.history("tenant-a", T1, T3)).isEmpty();
    }

    @Test
    void historyIncludesEventsAtFromInstant() {
        seed(triggered("sit-1", "key-1", "tenant-a", T3, T1));
        // [T3, T5) — T3 is inclusive
        assertThat(queryService.history("tenant-a", T3, T5)).hasSize(1);
    }

    @Test
    void historyOrderedChronologically() {
        seed(triggered("sit-1", "key-3", "tenant-a", T4, T1));
        seed(triggered("sit-1", "key-1", "tenant-a", T2, T1));
        seed(triggered("sit-1", "key-2", "tenant-a", T3, T1));
        var result = queryService.history("tenant-a", T1, T5);
        assertThat(result).hasSize(3);
        assertThat(result.get(0).eventTime()).isEqualTo(T2);
        assertThat(result.get(1).eventTime()).isEqualTo(T3);
        assertThat(result.get(2).eventTime()).isEqualTo(T4);
    }

    @Test
    void historyIncludesAllChangeTypes() {
        seed(triggered("sit-1", "key-1", "tenant-a", T2, T1));
        seed(resolved("sit-2", "key-1", "tenant-a", T3, T1));
        seed(event("sit-3", "key-1", "tenant-a",
                SituationChangeEvent.ChangeType.DISCARDED, T4, T1));
        assertThat(queryService.history("tenant-a", T1, T5)).hasSize(3);
    }

    @Test
    void historyBroadOverloadReturnsAllSituations() {
        seed(triggered("sit-1", "key-1", "tenant-a", T2, T1));
        seed(triggered("sit-2", "key-1", "tenant-a", T3, T1));
        seed(triggered("sit-3", "key-1", "tenant-a", T4, T1));
        assertThat(queryService.history("tenant-a", T1, T5)).hasSize(3);
    }

    // === triggerCount() ===

    @Test
    void triggerCountZeroWhenEmpty() {
        assertThat(queryService.triggerCount("tenant-a", "sit-1", T1, T5)).isZero();
    }

    @Test
    void triggerCountCountsTriggeredEvents() {
        seed(triggered("sit-1", "key-1", "tenant-a", T2, T1));
        seed(triggered("sit-1", "key-2", "tenant-a", T3, T1));
        assertThat(queryService.triggerCount("tenant-a", "sit-1", T1, T5)).isEqualTo(2);
    }

    @Test
    void triggerCountExcludesNonTriggered() {
        seed(triggered("sit-1", "key-1", "tenant-a", T2, T1));
        seed(resolved("sit-1", "key-2", "tenant-a", T3, T1));
        seed(event("sit-1", "key-3", "tenant-a",
                SituationChangeEvent.ChangeType.DISCARDED, T4, T1));
        assertThat(queryService.triggerCount("tenant-a", "sit-1", T1, T5)).isEqualTo(1);
    }

    @Test
    void triggerCountFiltersBySituationId() {
        seed(triggered("sit-1", "key-1", "tenant-a", T2, T1));
        seed(triggered("sit-2", "key-1", "tenant-a", T3, T1));
        assertThat(queryService.triggerCount("tenant-a", "sit-1", T1, T5)).isEqualTo(1);
    }

    @Test
    void triggerCountFiltersByTenancy() {
        seed(triggered("sit-1", "key-1", "tenant-a", T2, T1));
        seed(triggered("sit-1", "key-1", "tenant-b", T3, T1));
        assertThat(queryService.triggerCount("tenant-a", "sit-1", T1, T5)).isEqualTo(1);
    }

    @Test
    void triggerCountFiltersByTimeRange() {
        seed(triggered("sit-1", "key-1", "tenant-a", T1, T1));
        seed(triggered("sit-1", "key-2", "tenant-a", T3, T1));
        seed(triggered("sit-1", "key-3", "tenant-a", T5, T1));
        assertThat(queryService.triggerCount("tenant-a", "sit-1", T2, T4)).isEqualTo(1);
    }

    // === trend() ===

    @Test
    void trendInsufficientDataWhenBaselineEmpty() {
        // window=[T4,T5), baseline=[T3,T4) — put 1 trigger in window, 0 in baseline
        seed(triggered("sit-1", "key-1", "tenant-a", T4, T1));
        var result = queryService.trend("tenant-a", "sit-1",
                Duration.ofHours(1), Duration.ofHours(1), T5);
        assertThat(result.direction()).isEqualTo(TrendResult.TrendDirection.INSUFFICIENT_DATA);
        assertThat(result.currentCount()).isEqualTo(1);
        assertThat(result.baselineCount()).isZero();
    }

    @Test
    void trendRising() {
        // baseline=[T3,T4): 1 trigger, window=[T4,T5): 3 triggers
        // rate ratio = 3.0 > 1.2 → RISING
        seed(triggered("sit-1", "key-1", "tenant-a", T3, T1));
        seed(triggered("sit-1", "key-1", "tenant-a",
                T4, T1));
        seed(triggered("sit-1", "key-2", "tenant-a",
                T4.plusSeconds(600), T1));
        seed(triggered("sit-1", "key-3", "tenant-a",
                T4.plusSeconds(1200), T1));
        var result = queryService.trend("tenant-a", "sit-1",
                Duration.ofHours(1), Duration.ofHours(1), T5);
        assertThat(result.direction()).isEqualTo(TrendResult.TrendDirection.RISING);
        assertThat(result.currentCount()).isEqualTo(3);
        assertThat(result.baselineCount()).isEqualTo(1);
    }

    @Test
    void trendFalling() {
        // baseline=[T3,T4): 3 triggers, window=[T4,T5): 1 trigger
        // rate ratio = 0.33 < 0.8 → FALLING
        seed(triggered("sit-1", "key-1", "tenant-a",
                T3, T1));
        seed(triggered("sit-1", "key-2", "tenant-a",
                T3.plusSeconds(600), T1));
        seed(triggered("sit-1", "key-3", "tenant-a",
                T3.plusSeconds(1200), T1));
        seed(triggered("sit-1", "key-4", "tenant-a", T4, T1));
        var result = queryService.trend("tenant-a", "sit-1",
                Duration.ofHours(1), Duration.ofHours(1), T5);
        assertThat(result.direction()).isEqualTo(TrendResult.TrendDirection.FALLING);
        assertThat(result.currentCount()).isEqualTo(1);
        assertThat(result.baselineCount()).isEqualTo(3);
    }

    @Test
    void trendStable() {
        // baseline=[T3,T4): 2 triggers, window=[T4,T5): 2 triggers
        // rate ratio = 1.0 → STABLE
        seed(triggered("sit-1", "key-1", "tenant-a", T3, T1));
        seed(triggered("sit-1", "key-2", "tenant-a",
                T3.plusSeconds(1800), T1));
        seed(triggered("sit-1", "key-3", "tenant-a", T4, T1));
        seed(triggered("sit-1", "key-4", "tenant-a",
                T4.plusSeconds(1800), T1));
        var result = queryService.trend("tenant-a", "sit-1",
                Duration.ofHours(1), Duration.ofHours(1), T5);
        assertThat(result.direction()).isEqualTo(TrendResult.TrendDirection.STABLE);
        assertThat(result.currentCount()).isEqualTo(2);
        assertThat(result.baselineCount()).isEqualTo(2);
    }

    @Test
    void trendNormalizesRateAcrossDifferentWindowSizes() {
        // baseline=[T1,T4) = 3 hours with 3 triggers (1/hour)
        // window=[T4,T5) = 1 hour with 1 trigger (1/hour)
        // Same rate despite different raw counts → STABLE
        seed(triggered("sit-1", "key-1", "tenant-a", T1, T1));
        seed(triggered("sit-1", "key-2", "tenant-a", T2, T1));
        seed(triggered("sit-1", "key-3", "tenant-a", T3, T1));
        seed(triggered("sit-1", "key-4", "tenant-a", T4, T1));
        var result = queryService.trend("tenant-a", "sit-1",
                Duration.ofHours(1), Duration.ofHours(3), T5);
        assertThat(result.direction()).isEqualTo(TrendResult.TrendDirection.STABLE);
        assertThat(result.currentCount()).isEqualTo(1);
        assertThat(result.baselineCount()).isEqualTo(3);
    }

    @Test
    void trendCountsTriggeredOnly() {
        // baseline has 1 TRIGGERED + 1 RESOLVED, window has 1 TRIGGERED
        // Only TRIGGERED events count: baseline=1, window=1 → STABLE
        seed(triggered("sit-1", "key-1", "tenant-a", T3, T1));
        seed(resolved("sit-1", "key-2", "tenant-a", T3.plusSeconds(1800), T1));
        seed(triggered("sit-1", "key-3", "tenant-a", T4, T1));
        var result = queryService.trend("tenant-a", "sit-1",
                Duration.ofHours(1), Duration.ofHours(1), T5);
        assertThat(result.direction()).isEqualTo(TrendResult.TrendDirection.STABLE);
        assertThat(result.currentCount()).isEqualTo(1);
        assertThat(result.baselineCount()).isEqualTo(1);
    }

    @Test
    void trendBothPeriodsEmptyReturnsInsufficientData() {
        var result = queryService.trend("tenant-a", "sit-1",
                Duration.ofHours(1), Duration.ofHours(1), T5);
        assertThat(result.direction()).isEqualTo(TrendResult.TrendDirection.INSUFFICIENT_DATA);
        assertThat(result.currentCount()).isZero();
        assertThat(result.baselineCount()).isZero();
    }

    // === health() ===

    @Test
    void healthEmptyWhenNoEvents() {
        var result = queryService.health("tenant-a", Duration.ofHours(4), T5);
        assertThat(result.tenancyId()).isEqualTo("tenant-a");
        assertThat(result.totalEvents()).isZero();
        assertThat(result.situations()).isEmpty();
    }

    @Test
    void healthAggregatesAcrossSituations() {
        seed(triggered("sit-1", "key-1", "tenant-a", T2, T1));
        seed(triggered("sit-2", "key-1", "tenant-a", T3, T1));
        seed(resolved("sit-2", "key-2", "tenant-a", T4, T1));
        var result = queryService.health("tenant-a", Duration.ofHours(5), T5);
        assertThat(result.totalEvents()).isEqualTo(3);
        assertThat(result.situations()).hasSize(2);
    }

    @Test
    void healthSummaryCountsSeparately() {
        seed(triggered("sit-1", "key-1", "tenant-a", T2, T1));
        seed(triggered("sit-1", "key-2", "tenant-a", T3, T1));
        seed(resolved("sit-1", "key-3", "tenant-a", T4, T1));
        var result = queryService.health("tenant-a", Duration.ofHours(5), T5);
        assertThat(result.situations()).hasSize(1);
        var summary = result.situations().get(0);
        assertThat(summary.situationId()).isEqualTo("sit-1");
        assertThat(summary.eventCount()).isEqualTo(3);
        assertThat(summary.triggerCount()).isEqualTo(2);
        assertThat(summary.lastEvent()).isEqualTo(T4);
    }

    @Test
    void healthFiltersByTenancy() {
        seed(triggered("sit-1", "key-1", "tenant-a", T2, T1));
        seed(triggered("sit-1", "key-1", "tenant-b", T3, T1));
        var result = queryService.health("tenant-a", Duration.ofHours(5), T5);
        assertThat(result.totalEvents()).isEqualTo(1);
    }

    @Test
    void healthFiltersByWindow() {
        seed(triggered("sit-1", "key-1", "tenant-a", T1, T1));
        seed(triggered("sit-1", "key-2", "tenant-a", T4, T1));
        // window = 1h → [T4, T5) — only the T4 event
        var result = queryService.health("tenant-a", Duration.ofHours(1), T5);
        assertThat(result.totalEvents()).isEqualTo(1);
        assertThat(result.windowStart()).isEqualTo(T4);
        assertThat(result.windowEnd()).isEqualTo(T5);
    }

    // === removeEventsBefore() ===

    @Test
    void removeEventsBeforeDeletesOldEvents() {
        seed(triggered("sit-1", "key-1", "tenant-a", T1, T1));
        seed(triggered("sit-1", "key-2", "tenant-a", T4, T1));
        retention.removeEventsBefore(T3);
        assertThat(queryService.history("tenant-a", T1, T5)).hasSize(1);
        assertThat(queryService.history("tenant-a", T1, T5).get(0).eventTime()).isEqualTo(T4);
    }

    @Test
    void removeEventsBeforeKeepsRecentEvents() {
        seed(triggered("sit-1", "key-1", "tenant-a", T3, T1));
        retention.removeEventsBefore(T2);
        assertThat(queryService.history("tenant-a", T1, T5)).hasSize(1);
    }

    @Test
    void removeEventsBeforeReturnsCount() {
        seed(triggered("sit-1", "key-1", "tenant-a", T1, T1));
        seed(triggered("sit-1", "key-2", "tenant-a", T2, T1));
        seed(triggered("sit-1", "key-3", "tenant-a", T4, T1));
        int removed = retention.removeEventsBefore(T3);
        assertThat(removed).isEqualTo(2);
    }

    @Test
    void removeEventsBeforeReturnsZeroWhenNothingRemoved() {
        seed(triggered("sit-1", "key-1", "tenant-a", T4, T1));
        int removed = retention.removeEventsBefore(T1);
        assertThat(removed).isZero();
    }
}
