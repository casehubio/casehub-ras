package io.casehub.ras.drools;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@DefaultBean
public class InMemoryDroolsSessionStore implements DroolsSessionStore {

    private record StampedSession(KieSession session, long generation) {}

    private final ConcurrentHashMap<DroolsSessionKey, StampedSession> cache = new ConcurrentHashMap<>();

    @Override
    public KieSession computeIfAbsent(DroolsSessionKey key,
                                      KieBase kieBase,
                                      KieSessionConfiguration config,
                                      long generation) {
        StampedSession cached = cache.get(key);
        if (cached != null) {
            if (cached.generation < generation) {
                cached.session.dispose();
                cache.remove(key);
            } else {
                return cached.session;
            }
        }
        KieSession session = kieBase.newKieSession(config, null);
        cache.put(key, new StampedSession(session, generation));
        return session;
    }

    @Override
    public void remove(DroolsSessionKey key) {
        StampedSession removed = cache.remove(key);
        if (removed != null) {
            removed.session.dispose();
        }
    }

    public void removeAll(String ganglionId) {
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().ganglionId().equals(ganglionId)) {
                entry.getValue().session.dispose();
                iterator.remove();
            }
        }
    }
}
