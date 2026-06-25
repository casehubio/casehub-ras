package io.casehub.ras.drools;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.kie.api.runtime.KieSession;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@DefaultBean
public class InMemoryDroolsSessionStore implements DroolsSessionStore {

    private record SessionKey(String ganglionId, String situationId, String correlationKey, String tenancyId) {}

    private final ConcurrentHashMap<SessionKey, KieSession> sessions = new ConcurrentHashMap<>();

    @Override
    public Optional<KieSession> get(String ganglionId, String situationId, String correlationKey, String tenancyId) {
        return Optional.ofNullable(sessions.get(new SessionKey(ganglionId, situationId, correlationKey, tenancyId)));
    }

    @Override
    public void put(String ganglionId, String situationId, String correlationKey, String tenancyId, KieSession session) {
        var key = new SessionKey(ganglionId, situationId, correlationKey, tenancyId);
        KieSession old = sessions.put(key, session);
        if (old != null && old != session) {
            old.dispose();
        }
    }

    @Override
    public void remove(String ganglionId, String situationId, String correlationKey, String tenancyId) {
        KieSession removed = sessions.remove(new SessionKey(ganglionId, situationId, correlationKey, tenancyId));
        if (removed != null) {
            removed.dispose();
        }
    }
}
