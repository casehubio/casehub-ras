package io.casehub.ras.runtime;

import io.casehub.ras.api.GanglionStateStore;
import io.casehub.ras.api.SituationStore;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class SituationExpiryJob {

    private final SituationStore              store;
    private final GanglionStateStore          ganglionStateStore;
    private final SituationDefinitionRegistry registry;
    private final Duration                    triggerGuardPeriod;
    private final RasMetrics                  metrics;

    @Inject
    public SituationExpiryJob(
            SituationStore store,
            GanglionStateStore ganglionStateStore,
            SituationDefinitionRegistry registry,
            @ConfigProperty(name = "ras.evaluator.trigger-guard-period", defaultValue = "PT1M")
            Duration triggerGuardPeriod,
            RasMetrics metrics) {
        this.store              = store;
        this.ganglionStateStore = ganglionStateStore;
        this.registry           = registry;
        this.triggerGuardPeriod = triggerGuardPeriod;
        this.metrics            = metrics;
    }

    @Scheduled(every = "PT5M")
    void cleanup() {
        Instant guardCutoff      = Instant.now().minus(triggerGuardPeriod);
        int     triggeredRemoved = store.removeTriggeredBefore(guardCutoff).await().indefinitely();
        metrics.triggeredCleaned(triggeredRemoved);

        Duration maxWindow = registry.maxCorrelationWindow();
        if (maxWindow != null) {
            Instant cutoff         = Instant.now().minus(maxWindow);
            int     expiredRemoved = store.removeExpired(cutoff).await().indefinitely();
            metrics.expiredCleaned(expiredRemoved);
        }

        int orphanedRemoved = ganglionStateStore.removeOrphaned().await().indefinitely();
        metrics.orphanedGanglionStateCleaned(orphanedRemoved);
    }
}
