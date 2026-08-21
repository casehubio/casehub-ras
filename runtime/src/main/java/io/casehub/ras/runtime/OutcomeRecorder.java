package io.casehub.ras.runtime;

import io.casehub.api.spi.CaseOutcomeEvent;
import io.casehub.api.spi.CaseOutcomeObserver;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.GanglionContribution;
import io.casehub.ras.api.OutcomeLedger;
import io.casehub.ras.api.OutcomeRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
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
        if (snapshot == null) {return;}

        Object sitObj  = snapshot.get("situationId");
        Object corrObj = snapshot.get("correlationKey");
        if (sitObj == null || corrObj == null) {return;}

        String situationId    = sitObj.toString();
        String correlationKey = corrObj.toString();
        String tenancyId      = event.tenancyId();

        FeedbackConfig config = registry.feedbackConfig(situationId);
        if (config == null) {return;}

        List<GanglionContribution> contributions = extractContributions(snapshot);

        try {
            ledger.record(new OutcomeRecord(
                    situationId, correlationKey, tenancyId,
                    event.outcomeLabel(), config.classify(event.outcomeLabel()),
                    event.closedAt(), event.caseId(), contributions));
        } catch (RuntimeException ex) {
            LOG.warning("Failed to record outcome for situation '" + situationId
                        + "', case " + event.caseId() + ": " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    List<GanglionContribution> extractContributions(Map<String, Object> snapshot) {
        Object detectionsObj = snapshot.get("detections");
        if (detectionsObj == null) {return List.of();}

        List<Map<String, Object>> rawDetections;
        try {
            rawDetections = (List<Map<String, Object>>) detectionsObj;
        } catch (ClassCastException ex) {
            LOG.fine("Cannot parse detections from case file snapshot: " + ex.getMessage());
            return List.of();
        }

        Map<String, GanglionContribution> best = new LinkedHashMap<>();
        for (Map<String, Object> det : rawDetections) {
            try {
                Map<String, Object> result = (Map<String, Object>) det.get("result");
                if (result == null) {continue;}

                String ganglionId = (String) result.get("ganglionId");
                if (ganglionId == null) {continue;}
                DetectionSignal signal     = DetectionSignal.valueOf((String) result.get("signal"));
                double          confidence = ((Number) result.get("confidence")).doubleValue();

                best.merge(ganglionId,
                           new GanglionContribution(ganglionId, confidence, signal),
                           (existing, incoming) -> {
                               int cmp = incoming.signal().compareTo(existing.signal());
                               if (cmp == 0) {cmp = Double.compare(incoming.confidence(), existing.confidence());}
                               return cmp > 0 ? incoming : existing;
                           });
            } catch (RuntimeException ex) {
                LOG.fine("Skipping malformed detection entry: " + ex.getMessage());
            }
        }
        return List.copyOf(best.values());
    }

}
