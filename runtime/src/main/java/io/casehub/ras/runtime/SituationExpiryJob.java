package io.casehub.ras.runtime;

import io.casehub.ras.api.SituationStore;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class SituationExpiryJob {

    private final SituationStore store;
    private final SituationDefinitionRegistry registry;

    @Inject
    public SituationExpiryJob(SituationStore store, SituationDefinitionRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    @Scheduled(every = "PT5M")
    void cleanup() {
        Duration maxWindow = registry.maxCorrelationWindow();
        if (maxWindow == null) return;
        Instant cutoff = Instant.now().minus(maxWindow);
        store.removeExpired(cutoff).await().indefinitely();
    }
}
