package io.casehub.ras.runtime;

import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.OutcomeLedger;
import io.casehub.ras.api.OutcomeStatistics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;

@ApplicationScoped
public class FeedbackAnalyzer {

    private final OutcomeLedger ledger;

    @Inject
    public FeedbackAnalyzer(OutcomeLedger ledger) {
        this.ledger = ledger;
    }

    public OutcomeStatistics analyze(String situationId, String tenancyId, FeedbackConfig config) {
        Instant since = Instant.now().minus(config.retentionPeriod());
        return ledger.statistics(situationId, tenancyId, since);
    }

    public java.util.Map<String, io.casehub.ras.api.GanglionOutcomeStatistics> ganglionAnalyze(
            String situationId, String tenancyId, FeedbackConfig config) {
        Instant since = Instant.now().minus(config.retentionPeriod());
        return ledger.ganglionStatistics(situationId, tenancyId, since);
    }

}
