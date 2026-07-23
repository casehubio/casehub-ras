package io.casehub.ras.runtime;

import io.casehub.ras.api.OrphanedResourceCleaner;
import io.casehub.ras.api.SituationStore;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class SituationExpiryJob {

    private static final org.jboss.logging.Logger log = org.jboss.logging.Logger.getLogger(SituationExpiryJob.class);

    private final SituationStore                    store;
    private final SituationDefinitionRegistry       registry;
    private final Duration                          triggerGuardPeriod;
    private final RasMetrics                        metrics;
    private final Iterable<OrphanedResourceCleaner> resourceCleaners;

    @Inject
    public SituationExpiryJob(
            SituationStore store,
            SituationDefinitionRegistry registry,
            @ConfigProperty(name = "ras.evaluator.trigger-guard-period", defaultValue = "PT1M")
            Duration triggerGuardPeriod,
            RasMetrics metrics,
            Instance<OrphanedResourceCleaner> resourceCleaners) {
        this.store              = store;
        this.registry           = registry;
        this.triggerGuardPeriod = triggerGuardPeriod;
        this.metrics            = metrics;
        this.resourceCleaners   = resourceCleaners;
    }

    SituationExpiryJob(SituationStore store,
                       SituationDefinitionRegistry registry,
                       Duration triggerGuardPeriod,
                       RasMetrics metrics,
                       Iterable<OrphanedResourceCleaner> resourceCleaners) {
        this.store              = store;
        this.registry           = registry;
        this.triggerGuardPeriod = triggerGuardPeriod;
        this.metrics            = metrics;
        this.resourceCleaners   = resourceCleaners;
    }

    @Scheduled(every = "PT5M")
    void cleanup() {
        Instant guardCutoff      = Instant.now().minus(triggerGuardPeriod);
        int     triggeredRemoved = store.removeTriggeredBefore(guardCutoff);
        metrics.triggeredCleaned(triggeredRemoved);

        Duration maxWindow = registry.maxCorrelationWindow();
        if (maxWindow != null) {
            Instant cutoff         = Instant.now().minus(maxWindow);
            int     expiredRemoved = store.removeExpired(cutoff);
            metrics.expiredCleaned(expiredRemoved);
        }

        for (OrphanedResourceCleaner cleaner : resourceCleaners) {
            try {
                int cleaned = cleaner.removeOrphaned();
                metrics.orphanedResourcesCleaned(cleaned, cleaner.cleanerType());
            } catch (Exception e) {
                log.warnf(e, "Orphan cleaner '%s' failed, skipping", cleaner.cleanerType());
            }
        }}
}
