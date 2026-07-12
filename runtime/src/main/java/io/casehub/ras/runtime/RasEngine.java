package io.casehub.ras.runtime;

import io.casehub.ras.api.SituationRegistration;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.List;
import java.util.logging.Logger;

@ApplicationScoped
public class RasEngine {

    private static final Logger LOG = Logger.getLogger(RasEngine.class.getName());

    private final SituationDefinitionRegistry registry;
    private final SituationEvaluator evaluator;
    private final RasMetrics metrics;

    @Inject
    public RasEngine(SituationDefinitionRegistry registry, SituationEvaluator evaluator,
                     RasMetrics metrics) {
        this.registry = registry;
        this.evaluator = evaluator;
        this.metrics = metrics;
    }

    void onCloudEvent(@ObservesAsync CloudEvent event) {
        metrics.eventReceived(event.getType());

        String tenancyId = extractTenancyId(event);
        if (tenancyId == null) {
            LOG.warning("CloudEvent without tenancyid extension — skipping: " + event.getType());
            metrics.eventSkipped("no_tenancy_id");
            return;
        }

        List<SituationRegistration> registrations = registry.findByEventType(event.getType());
        if (registrations.isEmpty()) {
            metrics.eventSkipped("no_matching_situation");
            return;
        }

        for (SituationRegistration reg : registrations) {
            try {
                String correlationKey = reg.correlationKeyExtractor().extract(event);
                metrics.eventRouted(reg.definition().situationId(), tenancyId);
                evaluator.evaluate(event, reg.definition(), correlationKey, tenancyId);
            } catch (RuntimeException ex) {
                LOG.warning("Evaluation failed for situation '" + reg.definition().situationId()
                            + "': " + ex.getMessage());
                metrics.evaluationFailed(reg.definition().situationId(), tenancyId);
            }
        }
    }

    private String extractTenancyId(CloudEvent event) {
        Object ext = event.getExtension("tenancyid");
        return ext != null ? ext.toString() : null;
    }
}
