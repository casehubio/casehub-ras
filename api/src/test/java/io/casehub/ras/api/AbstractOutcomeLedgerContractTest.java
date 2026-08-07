package io.casehub.ras.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public abstract class AbstractOutcomeLedgerContractTest {

    protected abstract OutcomeLedger createLedger();

    private OutcomeLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = createLedger();
    }

    @Test
    void recordAndStatistics() {
        Instant since = Instant.now().minusSeconds(3600);
        ledger.record(outcome("s1", "k1", "t1", "dismissed",
                OutcomeClassification.NOISE, Instant.now(), UUID.randomUUID()));
        ledger.record(outcome("s1", "k1", "t1", "escalated",
                OutcomeClassification.CONFIRMED, Instant.now(), UUID.randomUUID()));
        ledger.record(outcome("s1", "k1", "t1", "closed",
                OutcomeClassification.NEUTRAL, Instant.now(), UUID.randomUUID()));

        OutcomeStatistics stats = ledger.statistics("s1", "t1", since);
        assertEquals(3, stats.totalOutcomes());
        assertEquals(1, stats.noiseCount());
        assertEquals(1, stats.confirmedCount());
        assertEquals(1, stats.neutralCount());
    }

    @Test
    void lastNoiseDismissalTimeReturnsMostRecent() {
        Instant early = Instant.now().minusSeconds(3600);
        Instant late = Instant.now().minusSeconds(60);
        ledger.record(outcome("s1", "k1", "t1", "dismissed",
                OutcomeClassification.NOISE, early, UUID.randomUUID()));
        ledger.record(outcome("s1", "k1", "t1", "dismissed",
                OutcomeClassification.NOISE, late, UUID.randomUUID()));
        ledger.record(outcome("s1", "k1", "t1", "escalated",
                OutcomeClassification.CONFIRMED, Instant.now(), UUID.randomUUID()));

        Optional<Instant> result = ledger.lastNoiseDismissalTime("s1", "k1", "t1");
        assertTrue(result.isPresent());
        assertEquals(late, result.get());
    }

    @Test
    void lastNoiseDismissalTimeEmptyWhenNoRecords() {
        assertTrue(ledger.lastNoiseDismissalTime("s1", "k1", "t1").isEmpty());
    }

    @Test
    void lastNoiseDismissalTimeEmptyWhenOnlyConfirmed() {
        ledger.record(outcome("s1", "k1", "t1", "escalated",
                OutcomeClassification.CONFIRMED, Instant.now(), UUID.randomUUID()));
        assertTrue(ledger.lastNoiseDismissalTime("s1", "k1", "t1").isEmpty());
    }

    @Test
    void multiTenantIsolation() {
        Instant since = Instant.now().minusSeconds(3600);
        ledger.record(outcome("s1", "k1", "tenantA", "dismissed",
                OutcomeClassification.NOISE, Instant.now(), UUID.randomUUID()));
        ledger.record(outcome("s1", "k1", "tenantB", "escalated",
                OutcomeClassification.CONFIRMED, Instant.now(), UUID.randomUUID()));

        assertEquals(1, ledger.statistics("s1", "tenantA", since).noiseCount());
        assertEquals(0, ledger.statistics("s1", "tenantA", since).confirmedCount());
        assertEquals(0, ledger.statistics("s1", "tenantB", since).noiseCount());
        assertEquals(1, ledger.statistics("s1", "tenantB", since).confirmedCount());
    }

    @Test
    void removeRecordsBefore() {
        Instant cutoff = Instant.now();
        ledger.record(outcome("s1", "k1", "t1", "dismissed",
                OutcomeClassification.NOISE, cutoff.minusSeconds(60), UUID.randomUUID()));
        ledger.record(outcome("s1", "k1", "t1", "escalated",
                OutcomeClassification.CONFIRMED, cutoff.plusSeconds(60), UUID.randomUUID()));

        int removed = ledger.removeRecordsBefore("s1", cutoff);
        assertEquals(1, removed);
        assertEquals(1, ledger.statistics("s1", "t1", Instant.EPOCH).totalOutcomes());
    }

    @Test
    void duplicateCaseIdIgnored() {
        UUID caseId = UUID.randomUUID();
        ledger.record(outcome("s1", "k1", "t1", "dismissed",
                OutcomeClassification.NOISE, Instant.now(), caseId));
        ledger.record(outcome("s1", "k1", "t1", "dismissed",
                OutcomeClassification.NOISE, Instant.now(), caseId));

        assertEquals(1, ledger.statistics("s1", "t1", Instant.EPOCH).totalOutcomes());
    }

    @Test
    void countByLabel() {
        Instant since = Instant.now().minusSeconds(3600);
        ledger.record(outcome("s1", "k1", "t1", "dismissed",
                OutcomeClassification.NOISE, Instant.now(), UUID.randomUUID()));
        ledger.record(outcome("s1", "k1", "t1", "dismissed",
                OutcomeClassification.NOISE, Instant.now(), UUID.randomUUID()));
        ledger.record(outcome("s1", "k1", "t1", "escalated",
                OutcomeClassification.CONFIRMED, Instant.now(), UUID.randomUUID()));

        Map<String, Long> counts = ledger.countByLabel("s1", "t1", since);
        assertEquals(2L, counts.get("dismissed"));
        assertEquals(1L, counts.get("escalated"));
    }

    @Test
    void distinctTenancies() {
        ledger.record(outcome("s1", "k1", "tenantA", "dismissed",
                OutcomeClassification.NOISE, Instant.now(), UUID.randomUUID()));
        ledger.record(outcome("s1", "k1", "tenantB", "escalated",
                OutcomeClassification.CONFIRMED, Instant.now(), UUID.randomUUID()));

        Set<String> tenancies = ledger.distinctTenancies("s1");
        assertEquals(Set.of("tenantA", "tenantB"), tenancies);
    }

    @Test
    void distinctTenanciesEmptyWhenNoRecords() {
        assertTrue(ledger.distinctTenancies("s1").isEmpty());
    }

    @Test
    void statisticsEmptyWhenNoRecords() {
        OutcomeStatistics stats = ledger.statistics("s1", "t1", Instant.EPOCH);
        assertEquals(0, stats.totalOutcomes());
        assertTrue(Double.isNaN(stats.precision()));
        assertTrue(Double.isNaN(stats.noiseRate()));
    }

    @Test
    void statisticsRespectsWindowStart() {
        Instant boundary = Instant.now();
        ledger.record(outcome("s1", "k1", "t1", "dismissed",
                OutcomeClassification.NOISE, boundary.minusSeconds(60), UUID.randomUUID()));
        ledger.record(outcome("s1", "k1", "t1", "escalated",
                OutcomeClassification.CONFIRMED, boundary.plusSeconds(60), UUID.randomUUID()));

        OutcomeStatistics stats = ledger.statistics("s1", "t1", boundary);
        assertEquals(1, stats.totalOutcomes());
        assertEquals(0, stats.noiseCount());
        assertEquals(1, stats.confirmedCount());
    }

    protected OutcomeRecord outcome(String situationId, String correlationKey,
            String tenancyId, String label, OutcomeClassification classification,
            Instant closedAt, UUID caseId) {
        return new OutcomeRecord(situationId, correlationKey, tenancyId,
                label, classification, closedAt, caseId);
    }
}
