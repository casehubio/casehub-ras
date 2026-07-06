package io.casehub.ras.drools.reliability;

import io.casehub.ras.drools.DroolsSessionKey;
import io.casehub.ras.drools.DroolsSessionStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.drools.core.common.Storage;
import org.drools.reliability.core.ReliableGlobalResolverFactory;
import org.drools.reliability.core.SimpleReliableObjectStoreFactory;
import org.drools.reliability.core.StorageManagerFactory;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.runtime.conf.PersistedSessionOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ReliableDroolsSessionStore implements DroolsSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ReliableDroolsSessionStore.class);

    private record StampedSession(KieSession session, long generation) {}

    private final ConcurrentHashMap<DroolsSessionKey, StampedSession> hotCache = new ConcurrentHashMap<>();
    private Storage<String, Long> sessionIds;
    private Storage<String, Long> sessionGenerations;

    @PostConstruct
    void init() {
        ReliableGlobalResolverFactory.get("core");
        SimpleReliableObjectStoreFactory.get("core");
        var sm = StorageManagerFactory.get("h2mvstore").getStorageManager();
        sessionIds = sm.getOrCreateSharedStorage("ras_drools_session_ids");
        sessionGenerations = sm.getOrCreateSharedStorage("ras_drools_session_gens");
        sessionGenerations.clear();
    }

    @PreDestroy
    void destroy() {
        hotCache.values().forEach(s -> {
            try { s.session.dispose(); } catch (Exception ignored) {}
        });
        hotCache.clear();
    }

    @Override
    public KieSession computeIfAbsent(DroolsSessionKey key,
                                      KieBase kieBase,
                                      KieSessionConfiguration config,
                                      long generation) {
        String storageKey = key.toStorageKey();

        StampedSession cached = hotCache.get(key);
        if (cached != null) {
            if (cached.generation < generation) {
                cached.session.dispose();
                hotCache.remove(key);
                removePersistedSession(storageKey);
            } else {
                return cached.session;
            }
        }

        Long savedId = sessionIds.get(storageKey);
        if (savedId != null) {
            Long savedGen = sessionGenerations.getOrDefault(storageKey, 0L);
            if (savedGen < generation) {
                removePersistedSession(storageKey);
            } else {
                try {
                    KieSession recovered = createRecoveredSession(kieBase, config, savedId);
                    sessionGenerations.put(storageKey, generation);
                    hotCache.put(key, new StampedSession(recovered, generation));
                    return recovered;
                } catch (RuntimeException ex) {
                    log.warn("Recovery failed for {}, creating fresh session", key, ex);
                    removePersistedSession(storageKey);
                }
            }
        }

        KieSession session = createNewPersistedSession(kieBase, config);
        sessionIds.put(storageKey, session.getIdentifier());
        sessionGenerations.put(storageKey, generation);
        hotCache.put(key, new StampedSession(session, generation));
        return session;
    }

    @Override
    public void remove(DroolsSessionKey key) {
        StampedSession removed = hotCache.remove(key);
        if (removed != null) {
            removed.session.dispose();
        }
        String storageKey = key.toStorageKey();
        Long savedId = sessionIds.remove(storageKey);
        sessionGenerations.remove(storageKey);
        if (savedId != null) {
            StorageManagerFactory.get().getStorageManager()
                    .removeStoragesBySessionId(String.valueOf(savedId));
        }
    }

    private KieSession createNewPersistedSession(KieBase kieBase, KieSessionConfiguration callerConfig) {
        KieSessionConfiguration storeConfig = buildStoreConfig(callerConfig);
        storeConfig.setOption(PersistedSessionOption.newSession()
                .withPersistenceStrategy(PersistedSessionOption.PersistenceStrategy.STORES_ONLY)
                .withSafepointStrategy(PersistedSessionOption.SafepointStrategy.AFTER_FIRE)
                .withActivationStrategy(PersistedSessionOption.ActivationStrategy.ACTIVATION_KEY));
        return kieBase.newKieSession(storeConfig, null);
    }

    private KieSession createRecoveredSession(KieBase kieBase, KieSessionConfiguration callerConfig, long savedId) {
        KieSessionConfiguration storeConfig = buildStoreConfig(callerConfig);
        storeConfig.setOption(PersistedSessionOption.fromSession(savedId)
                .withPersistenceStrategy(PersistedSessionOption.PersistenceStrategy.STORES_ONLY)
                .withSafepointStrategy(PersistedSessionOption.SafepointStrategy.AFTER_FIRE)
                .withActivationStrategy(PersistedSessionOption.ActivationStrategy.ACTIVATION_KEY));
        return kieBase.newKieSession(storeConfig, null);
    }

    private KieSessionConfiguration buildStoreConfig(KieSessionConfiguration callerConfig) {
        KieSessionConfiguration storeConfig = KieServices.Factory.get().newKieSessionConfiguration();
        storeConfig.setOption(callerConfig.getOption(ClockTypeOption.KEY));
        return storeConfig;
    }

    /**
     * Test-only: clears the hot cache without disposing sessions — simulates JVM crash
     * where sessions are abandoned. Package-private for test access.
     */
    void clearHotCacheForTest() {
        hotCache.clear();
    }

    private void removePersistedSession(String storageKey) {
        Long savedId = sessionIds.remove(storageKey);
        sessionGenerations.remove(storageKey);
        if (savedId != null) {
            StorageManagerFactory.get().getStorageManager()
                    .removeStoragesBySessionId(String.valueOf(savedId));
        }
    }
}
