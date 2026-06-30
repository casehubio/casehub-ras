package io.casehub.ras.runtime;

import io.casehub.ras.api.SituationStore;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class SituationExpiryJob {

    private final SituationStore store;
    private final SituationDefinitionRegistry registry;
    private final Duration triggerGuardPeriod;

    @Inject
    public SituationExpiryJob(
            SituationStore store,
            SituationDefinitionRegistry registry,
            @ConfigProperty(name = "ras.evaluator.trigger-guard-period", defaultValue = "PT1M")
            Duration triggerGuardPeriod) {
        this.store = store;
        this.registry = registry;
        this.triggerGuardPeriod = triggerGuardPeriod;
    }

    @Scheduled(every = "PT5M")
    void cleanup() {
        // Guard cleanup — runs every time regardless of windowed situations
        Instant guardCutoff = Instant.now().minus(triggerGuardPeriod);
        store.removeTriggeredBefore(guardCutoff).await().indefinitely();

        // Windowed situation cleanup — only when windowed definitions exist
        Duration maxWindow = registry.maxCorrelationWindow();
        if (maxWindow != null) {
            Instant cutoff = Instant.now().minus(maxWindow);
            store.removeExpired(cutoff).await().indefinitely();
        }
    }
}
