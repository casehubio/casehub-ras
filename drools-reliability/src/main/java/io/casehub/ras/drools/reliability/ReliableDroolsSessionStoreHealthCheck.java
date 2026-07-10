package io.casehub.ras.drools.reliability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.drools.reliability.core.StorageManagerFactory;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class ReliableDroolsSessionStoreHealthCheck implements HealthCheck {

    private static final String PROBE_KEY = "ras_drools_health_probe";

    private final ReliableDroolsSessionStore store;

    @Inject
    public ReliableDroolsSessionStoreHealthCheck(ReliableDroolsSessionStore store) {
        this.store = store;
    }

    @Override
    public HealthCheckResponse call() {
        try {
            StorageManagerFactory.get().getStorageManager()
                    .getOrCreateSharedStorage(PROBE_KEY);
            return HealthCheckResponse.named("drools-session-store")
                    .up()
                    .withData("store", "h2mvstore")
                    .withData("activeSessions", (long) store.activeSessionCount())
                    .build();
        } catch (RuntimeException ex) {
            return HealthCheckResponse.named("drools-session-store")
                    .down()
                    .withData("store", "h2mvstore")
                    .withData("error", ex.getMessage())
                    .build();
        }
    }
}
