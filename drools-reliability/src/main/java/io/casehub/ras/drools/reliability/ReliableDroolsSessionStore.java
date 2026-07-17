package io.casehub.ras.drools.reliability;

import io.casehub.ras.api.OrphanedResourceCleaner;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationStore;
import io.smallrye.mutiny.Uni;
import io.casehub.ras.drools.DroolsSessionKey;
import io.casehub.ras.drools.DroolsSessionStore;
import io.casehub.ras.drools.DroolsSessionStoreException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.drools.core.common.Storage;
import org.drools.reliability.core.ReliableGlobalResolverFactory;
import org.drools.reliability.core.SimpleReliableObjectStoreFactory;
import org.drools.reliability.core.StorageManagerFactory;
import org.drools.reliability.h2mvstore.H2MVStoreStorageManager;
import org.h2.mvstore.MVStore;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.runtime.conf.PersistedSessionOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ReliableDroolsSessionStore implements DroolsSessionStore, OrphanedResourceCleaner {

    private static final Logger log = LoggerFactory.getLogger(ReliableDroolsSessionStore.class);
    private static volatile boolean storageProbed = false;

    private record StampedSession(KieSession session, long generation) {}

    private final ConcurrentHashMap<DroolsSessionKey, StampedSession> hotCache = new ConcurrentHashMap<>();
    private       Storage<String, Long>                               sessionIds;
    private       Storage<String, Long>                               sessionGenerations;

    @Inject
    Instance<DroolsReliabilityMetrics> metricsInstance;

    private DroolsReliabilityMetrics metrics;

    void setMetrics(DroolsReliabilityMetrics metrics) {
        this.metrics = metrics;
    }

    @Inject
    Instance<SituationStore> situationStoreInstance;

    private          SituationStore situationStore;
    private volatile boolean        closed = false;

    void setSituationStore(SituationStore situationStore) {
        this.situationStore = situationStore;
    }


    @PostConstruct
    void init() {
        ReliableGlobalResolverFactory.get("core");
        SimpleReliableObjectStoreFactory.get("core");
        recoverCorruptStoreIfNeeded();
        var sm = StorageManagerFactory.get("h2mvstore").getStorageManager();
        sessionIds         = sm.getOrCreateSharedStorage("ras_drools_session_ids");
        sessionGenerations = sm.getOrCreateSharedStorage("ras_drools_session_gens");
        sessionGenerations.clear();
        if (metrics == null && metricsInstance != null && metricsInstance.isResolvable()) {
            metrics = metricsInstance.get();
        }
        if (metrics != null) {
            metrics.registerActiveSessionsGauge(hotCache::size);
        }
        if (situationStore == null && situationStoreInstance != null && situationStoreInstance.isResolvable()) {
            situationStore = situationStoreInstance.get();
        }
        log.info("DroolsSessionStore initialized: h2mvstore, {} persisted session(s)", sessionIds.size());
    }

    int activeSessionCount() {
        return hotCache.size();
    }

    @PreDestroy
    void destroy() {
        closed = true;
        int count = hotCache.size();
        hotCache.clear();
        log.info("DroolsSessionStore shutdown: {} sessions released from hot cache (persisted data preserved)", count);
    }

    @Override
    public String cleanerType() {
        return "drools_session";
    }

    @Override
    public Uni<Integer> removeOrphaned() {
        if (closed) {
            return Uni.createFrom().item(0);
        }
        int          removed     = 0;
        List<String> storageKeys = new ArrayList<>(sessionIds.keySet());
        for (String storageKey : storageKeys) {
            try {
                DroolsSessionKey key = DroolsSessionKey.fromStorageKey(storageKey);
                if (situationStore == null) {
                    remove(key);
                    removed++;
                } else {
                    Optional<SituationContext> ctx = situationStore
                                                             .find(key.situationId(), key.correlationKey(), key.tenancyId())
                                                             .await().indefinitely();
                    if (ctx.isEmpty()) {
                        remove(key);
                        removed++;
                    }
                }
            } catch (Exception e) {
                log.warn("Orphan cleanup failed for key '{}', skipping", storageKey, e);
            }
        }
        return Uni.createFrom().item(removed);
    }


    @Override
    public KieSession computeIfAbsent(DroolsSessionKey key,
                                      KieBase kieBase,
                                      KieSessionConfiguration config,
                                      long generation) {
        String storageKey = key.toStorageKey();
        Object sample     = metrics != null ? metrics.startComputeTimer() : null;

        StampedSession cached = hotCache.get(key);
        if (cached != null) {
            if (cached.generation < generation) {
                cached.session.dispose();
                hotCache.remove(key);
                removePersistedSession(storageKey);
                if (metrics != null) {metrics.sessionEvicted(key.ganglionId());}
            } else {
                if (metrics != null) {metrics.stopComputeTimer(sample, key.ganglionId(), "hit");}
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
                if (metrics != null) {metrics.sessionEvicted(key.ganglionId());}
            } else {
                try {
                    KieSession recovered = createRecoveredSession(kieBase, config, savedId);
                    try {
                        sessionGenerations.put(storageKey, generation);
                    } catch (RuntimeException ex) {
                        log.error("Storage write failed for key '{}' — recovered session usable but generation not durable", storageKey, ex);
                        if (metrics != null) {metrics.storeWriteFailed(key.ganglionId());}
                    }
                    hotCache.put(key, new StampedSession(recovered, generation));
                    if (metrics != null) {metrics.sessionRecovered(key.ganglionId());}
                    if (metrics != null) {metrics.stopComputeTimer(sample, key.ganglionId(), "recovered");}
                    return recovered;
                } catch (RuntimeException ex) {
                    log.warn("Recovery failed for {}, creating fresh session", key, ex);
                    if (metrics != null) {metrics.sessionRecoveryFailed(key.ganglionId());}
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
            if (metrics != null) {metrics.storeWriteFailed(key.ganglionId());}
        }
        hotCache.put(key, new StampedSession(session, generation));
        if (metrics != null) {metrics.sessionCreated(key.ganglionId());}
        if (metrics != null) {metrics.stopComputeTimer(sample, key.ganglionId(), "created");}
        return session;
    }

    @Override
    public void remove(DroolsSessionKey key) {
        StampedSession removed = hotCache.remove(key);
        if (removed != null) {
            removed.session.dispose();
        }
        String storageKey = key.toStorageKey();
        Long   savedId    = sessionIds.remove(storageKey);
        sessionGenerations.remove(storageKey);
        if (savedId != null) {
            StorageManagerFactory.get().getStorageManager()
                                 .removeStoragesBySessionId(String.valueOf(savedId));
        }
        if (metrics != null) {metrics.sessionRemoved(key.ganglionId());}
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

    static void resetStorageProbeForTest() {
        storageProbed = false;
    }


    private void recoverCorruptStoreIfNeeded() {
        recoverCorruptStoreIfNeeded(Path.of(H2MVStoreStorageManager.STORE_FILE_NAME));
    }

    void recoverCorruptStoreIfNeeded(Path storeFile) {
        if (storageProbed) {
            return;
        }
        storageProbed = true;
        if (!Files.exists(storeFile)) {
            return;
        }
        MVStore probe = null;
        try {
            probe = new MVStore.Builder().fileName(storeFile.toString()).open();
        } catch (Exception ex) {
            log.warn("H2MVStore corruption detected — initiating automatic recovery", ex);
            renameCorruptFile(storeFile);
            if (metrics != null) {
                metrics.storeCorruptionRecovered();
            }
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void renameCorruptFile(Path storeFile) {
        String timestamp  = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path   backupPath = storeFile.resolveSibling(storeFile.getFileName() + ".corrupt." + timestamp);
        try {
            Files.move(storeFile, backupPath);
            log.warn("Corrupt store renamed to {} — all persisted Drools sessions lost", backupPath);
        } catch (IOException moveEx) {
            log.error("Failed to rename corrupt store — attempting delete", moveEx);
            try {
                Files.deleteIfExists(storeFile);
                log.warn("Corrupt store deleted — all persisted Drools sessions lost");
            } catch (IOException deleteEx) {
                throw new RuntimeException(
                        "Cannot recover from corrupt H2MVStore — manual intervention required", deleteEx);
            }
        }
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
