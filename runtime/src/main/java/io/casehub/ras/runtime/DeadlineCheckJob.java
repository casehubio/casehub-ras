package io.casehub.ras.runtime;

import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationStore;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

@ApplicationScoped
public class DeadlineCheckJob {

    private static final Logger LOG = Logger.getLogger(DeadlineCheckJob.class.getName());

    private final SituationStore store;
    private final SituationDefinitionRegistry registry;
    private final SituationEvaluator evaluator;
    private final RasMetrics metrics;

    @Inject
    public DeadlineCheckJob(SituationStore store, SituationDefinitionRegistry registry,
                            SituationEvaluator evaluator, RasMetrics metrics) {
        this.store = store;
        this.registry = registry;
        this.evaluator = evaluator;
        this.metrics = metrics;
    }

    @Scheduled(every = "${ras.deadline.check-interval:PT10S}")
    void check() {
        Instant now = Instant.now();
        for (String situationId : registry.allSituationIds()) {
            SituationDefinition def = registry.definition(situationId);
            if (def == null || def.deadline() == null) continue;

            List<SituationContext> active = store.findActiveBySituationId(situationId);
            for (SituationContext ctx : active) {
                metrics.deadlineChecked(situationId);
                if (ctx.firstSignal().plus(def.deadline()).isBefore(now)) {
                    try {
                        evaluator.triggerByDeadline(
                                situationId, ctx.correlationKey(), ctx.tenancyId());
                        metrics.deadlineTriggered(situationId, ctx.tenancyId());
                    } catch (Exception ex) {
                        LOG.warning("Deadline trigger failed for situation '"
                                    + situationId + "': " + ex.getMessage());
                    }
                }
            }
        }
    }
}
