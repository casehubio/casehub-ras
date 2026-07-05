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

    @Inject
    public RasEngine(SituationDefinitionRegistry registry, SituationEvaluator evaluator) {
        this.registry = registry;
        this.evaluator = evaluator;
    }

    void onCloudEvent(@ObservesAsync CloudEvent event) {
        String tenancyId = extractTenancyId(event);
        if (tenancyId == null) {
            LOG.warning("CloudEvent without tenancyid extension — skipping: " + event.getType());
            return;
        }

        List<SituationRegistration> registrations = registry.findByEventType(event.getType());
        for (SituationRegistration reg : registrations) {
            try {
                String correlationKey = reg.correlationKeyExtractor().extract(event);
                evaluator.evaluate(event, reg.definition(), correlationKey, tenancyId);
            } catch (RuntimeException ex) {
                LOG.warning("Evaluation failed for situation '" + reg.definition().situationId()
                            + "': " + ex.getMessage());
            }
        }
    }

    private String extractTenancyId(CloudEvent event) {
        Object ext = event.getExtension("tenancyid");
        return ext != null ? ext.toString() : null;
    }
}
