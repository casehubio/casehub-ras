package io.casehub.ras.runtime;

import io.casehub.ras.api.OrphanedResourceCleaner;
import io.casehub.ras.api.SituationEventRetention;
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
    private final Duration                          eventHistoryRetention;
    private final RasMetrics                        metrics;
    private final Iterable<OrphanedResourceCleaner> resourceCleaners;
    private final Instance<SituationEventRetention> eventRetention;

    @Inject
    public SituationExpiryJob(
            SituationStore store,
            SituationDefinitionRegistry registry,
            @ConfigProperty(name = "ras.evaluator.trigger-guard-period", defaultValue = "PT1M")
            Duration triggerGuardPeriod,
            @ConfigProperty(name = "ras.event-history.retention", defaultValue = "P30D")
            Duration eventHistoryRetention,
            RasMetrics metrics,
            Instance<OrphanedResourceCleaner> resourceCleaners,
            Instance<SituationEventRetention> eventRetention) {
        this.store                 = store;
        this.registry              = registry;
        this.triggerGuardPeriod    = triggerGuardPeriod;
        this.eventHistoryRetention = eventHistoryRetention;
        this.metrics               = metrics;
        this.resourceCleaners      = resourceCleaners;
        this.eventRetention        = eventRetention;
    }

    SituationExpiryJob(SituationStore store,
                       SituationDefinitionRegistry registry,
                       Duration triggerGuardPeriod,
                       Duration eventHistoryRetention,
                       RasMetrics metrics,
                       Iterable<OrphanedResourceCleaner> resourceCleaners,
                       Instance<SituationEventRetention> eventRetention) {
        this.store                 = store;
        this.registry              = registry;
        this.triggerGuardPeriod    = triggerGuardPeriod;
        this.eventHistoryRetention = eventHistoryRetention;
        this.metrics               = metrics;
        this.resourceCleaners      = resourceCleaners;
        this.eventRetention        = eventRetention;
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
        }

        if (eventRetention != null && eventRetention.isResolvable()) {
            try {
                Instant eventCutoff   = Instant.now().minus(eventHistoryRetention);
                int     eventsCleaned = eventRetention.get().removeEventsBefore(eventCutoff);
                metrics.eventLogCleaned(eventsCleaned);
            } catch (Exception e) {
                log.warn("Event history cleanup failed, skipping", e);
            }
        }
    }
}
