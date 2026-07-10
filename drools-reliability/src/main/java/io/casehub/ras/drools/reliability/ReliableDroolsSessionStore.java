package io.casehub.ras.drools.reliability;

import io.casehub.ras.drools.DroolsSessionKey;
import io.casehub.ras.drools.DroolsSessionStore;
import io.casehub.ras.drools.DroolsSessionStoreException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ReliableDroolsSessionStore implements DroolsSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ReliableDroolsSessionStore.class);

    private record StampedSession(KieSession session, long generation) {}

    private final ConcurrentHashMap<DroolsSessionKey, StampedSession> hotCache = new ConcurrentHashMap<>();
    private Storage<String, Long> sessionIds;
    private Storage<String, Long> sessionGenerations;

    @Inject
    Instance<MeterRegistry> meterRegistryInstance;

    private MeterRegistry metrics;

    void setMeterRegistry(MeterRegistry registry) {
        this.metrics = registry;
    }

    @PostConstruct
    void init() {
        ReliableGlobalResolverFactory.get("core");
        SimpleReliableObjectStoreFactory.get("core");
        var sm = StorageManagerFactory.get("h2mvstore").getStorageManager();
        sessionIds = sm.getOrCreateSharedStorage("ras_drools_session_ids");
        sessionGenerations = sm.getOrCreateSharedStorage("ras_drools_session_gens");
        sessionGenerations.clear();
        if (metrics == null && meterRegistryInstance != null && meterRegistryInstance.isResolvable()) {
            metrics = meterRegistryInstance.get();
        }
        if (metrics != null) {
            metrics.gaugeMapSize("ras.drools.session.active", List.of(), hotCache);
        }
        log.info("DroolsSessionStore initialized: h2mvstore, {} persisted session(s)", sessionIds.size());
    }

    int activeSessionCount() {
        return hotCache.size();
    }

    @PreDestroy
    void destroy() {
        int count = hotCache.size();
        hotCache.clear();
        log.info("DroolsSessionStore shutdown: {} sessions released from hot cache (persisted data preserved)", count);
    }

    @Override
    public KieSession computeIfAbsent(DroolsSessionKey key,
                                      KieBase kieBase,
                                      KieSessionConfiguration config,
                                      long generation) {
        String storageKey = key.toStorageKey();
        Timer.Sample sample = startTimer();

        StampedSession cached = hotCache.get(key);
        if (cached != null) {
            if (cached.generation < generation) {
                cached.session.dispose();
                hotCache.remove(key);
                removePersistedSession(storageKey);
                incrementCounter("ras.drools.session.evicted", key.ganglionId());
            } else {
                stopTimer(sample, key.ganglionId(), "hit");
                return cached.session;
            }
        }

        Long savedId;
        try {
            savedId = sessionIds.get(storageKey);
        } catch (RuntimeException ex) {
            throw new DroolsSessionStoreException(
                    "storage read failed for key '" + storageKey + "'", ex);
        }
        if (savedId != null) {
            Long savedGen = sessionGenerations.getOrDefault(storageKey, 0L);
            if (savedGen < generation) {
                removePersistedSession(storageKey);
                incrementCounter("ras.drools.session.evicted", key.ganglionId());
            } else {
                try {
                    KieSession recovered = createRecoveredSession(kieBase, config, savedId);
                    try {
                        sessionGenerations.put(storageKey, generation);
                    } catch (RuntimeException ex) {
                        log.error("Storage write failed for key '{}' — recovered session usable but generation not durable", storageKey, ex);
                        incrementCounter("ras.drools.store.write_failed", key.ganglionId());
                    }
                    hotCache.put(key, new StampedSession(recovered, generation));
                    incrementCounter("ras.drools.session.recovered", key.ganglionId());
                    stopTimer(sample, key.ganglionId(), "recovered");
                    return recovered;
                } catch (RuntimeException ex) {
                    log.warn("Recovery failed for {}, creating fresh session", key, ex);
                    incrementCounter("ras.drools.session.recovery_failed", key.ganglionId());
                    try {
                        removePersistedSession(storageKey);
                    } catch (RuntimeException cleanupEx) {
                        log.warn("Cleanup after recovery failure also failed for {}", key, cleanupEx);
                    }
                }
            }
        }

        KieSession session = createNewPersistedSession(kieBase, config);
        try {
            sessionIds.put(storageKey, session.getIdentifier());
            sessionGenerations.put(storageKey, generation);
        } catch (RuntimeException ex) {
            log.error("Storage write failed for key '{}' — session usable but not durable", storageKey, ex);
            incrementCounter("ras.drools.store.write_failed", key.ganglionId());
        }
        hotCache.put(key, new StampedSession(session, generation));
        incrementCounter("ras.drools.session.created", key.ganglionId());
        stopTimer(sample, key.ganglionId(), "created");
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
        incrementCounter("ras.drools.session.removed", key.ganglionId());
    }

    private void incrementCounter(String name, String ganglionId) {
        if (metrics != null) {
            metrics.counter(name, "ganglion_id", ganglionId).increment();
        }
    }

    private Timer.Sample startTimer() {
        return metrics != null ? Timer.start(metrics) : null;
    }

    private void stopTimer(Timer.Sample sample, String ganglionId, String outcome) {
        if (sample != null) {
            sample.stop(metrics.timer("ras.drools.session.compute_time",
                    "ganglion_id", ganglionId, "outcome", outcome));
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
