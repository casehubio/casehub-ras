package io.casehub.ras.runtime;

import io.casehub.api.spi.CaseOutcomeEvent;
import io.casehub.api.spi.CaseOutcomeObserver;
import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.OutcomeLedger;
import io.casehub.ras.api.OutcomeRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class OutcomeRecorder implements CaseOutcomeObserver {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(OutcomeRecorder.class.getName());

    private final OutcomeLedger ledger;
    private final SituationDefinitionRegistry registry;

    @Inject
    public OutcomeRecorder(OutcomeLedger ledger, SituationDefinitionRegistry registry) {
        this.ledger = ledger;
        this.registry = registry;
    }

    @Override
    public void onOutcome(CaseOutcomeEvent event) {
        Map<String, Object> snapshot = event.caseFileSnapshot();
        if (snapshot == null) return;

        Object sitObj = snapshot.get("situationId");
        Object corrObj = snapshot.get("correlationKey");
        if (sitObj == null || corrObj == null) return;

        String situationId = sitObj.toString();
        String correlationKey = corrObj.toString();
        String tenancyId = event.tenancyId();

        FeedbackConfig config = registry.feedbackConfig(situationId);
        if (config == null) return;

        try {
            ledger.record(new OutcomeRecord(
                    situationId, correlationKey, tenancyId,
                    event.outcomeLabel(), config.classify(event.outcomeLabel()),
                    event.closedAt(), event.caseId()));
        } catch (RuntimeException ex) {
            LOG.warning("Failed to record outcome for situation '" + situationId
                        + "', case " + event.caseId() + ": " + ex.getMessage());
        }
    }
}
