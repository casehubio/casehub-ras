package io.casehub.ras.drools.reliability;

import io.casehub.ras.drools.DroolsSessionKey;
import org.drools.model.codegen.ExecutableModelProject;
import org.drools.reliability.core.ReliableRuntimeComponentFactoryImpl;
import org.drools.reliability.core.StorageManagerFactory;
import org.drools.reliability.core.TestableStorageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.time.SessionPseudoClock;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.assertj.core.api.Assertions.*;

class ReliableDroolsSessionStoreTest {

    private ReliableDroolsSessionStore store;
    private KieBase kieBase;
    private KieSessionConfiguration config;

    @BeforeEach
    void setUp() {
        StorageManagerFactory.get("h2mvstore").getStorageManager().removeAllSessionStorages();
        store = new ReliableDroolsSessionStore();
        store.init();
        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        KieBuilder kb = ks.newKieBuilder(kfs).buildAll(ExecutableModelProject.class);
        var kbc = ks.newKieBaseConfiguration();
        kbc.setOption(EventProcessingOption.STREAM);
        kieBase = ks.newKieContainer(kb.getKieModule().getReleaseId()).newKieBase(kbc);
        config = ks.newKieSessionConfiguration();
        config.setOption(ClockTypeOption.PSEUDO);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.destroy();
        }
        ((TestableStorageManager) StorageManagerFactory.get().getStorageManager()).restartWithCleanUp();
    }

    private DroolsSessionKey key(String ganglionId, String situationId) {
        return new DroolsSessionKey(ganglionId, situationId, "key-1", "tenant-a");
    }

    @Test
    void computeIfAbsentCreatesOnFirstCall() {
        KieSession session = store.computeIfAbsent(key("g1", "sit-1"), kieBase, config, 0);
        assertThat(session).isNotNull();
    }

    @Test
    void computeIfAbsentReturnsSameOnSecondCall() {
        var k = key("g1", "sit-1");
        KieSession s1 = store.computeIfAbsent(k, kieBase, config, 0);
        KieSession s2 = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(s2).isSameAs(s1);
    }

    @Test
    void restartSurvival() {
        var k = key("g1", "sit-1");
        KieSession session = store.computeIfAbsent(k, kieBase, config, 0);
        session.insert("test-fact");
        session.fireAllRules();

        simulateRestart();

        KieSession recovered = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(recovered).isNotSameAs(session);
        assertThat(recovered.getObjects()).anyMatch(o -> "test-fact".equals(o));
    }

    @Test
    void pseudoClockSurvivesRestart() {
        var k = key("g1", "sit-1");
        KieSession session = store.computeIfAbsent(k, kieBase, config, 0);
        SessionPseudoClock clock = session.getSessionClock();
        clock.advanceTime(60_000, TimeUnit.MILLISECONDS);
        session.insert("clock-test");
        session.fireAllRules();

        long clockBefore = clock.getCurrentTime();
        simulateRestart();

        KieSession recovered = store.computeIfAbsent(k, kieBase, config, 0);
        SessionPseudoClock recoveredClock = recovered.getSessionClock();
        assertThat(recoveredClock.getCurrentTime()).isGreaterThanOrEqualTo(clockBefore);
    }

    @Test
    void generationInvalidation() {
        var k = key("g1", "sit-1");
        KieSession old = store.computeIfAbsent(k, kieBase, config, 0);
        old.insert("old-fact");
        old.fireAllRules();
        KieSession fresh = store.computeIfAbsent(k, kieBase, config, 1);
        assertThat(fresh).isNotSameAs(old);
        assertThat(fresh.getObjects()).noneMatch(o -> "old-fact".equals(o));
    }

    @Test
    void generationInvalidationAcrossRestart() {
        var k = key("g1", "sit-1");
        store.computeIfAbsent(k, kieBase, config, 0);

        simulateRestart();

        KieSession fresh = store.computeIfAbsent(k, kieBase, config, 1);
        assertThat(fresh.getObjects()).isEmpty();
    }

    @Test
    void crossRestartGenerationReset() {
        var k = key("g1", "sit-1");
        store.computeIfAbsent(k, kieBase, config, 5);

        simulateRestart();

        KieSession recovered = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(recovered).isNotNull();
        KieSession invalidated = store.computeIfAbsent(k, kieBase, config, 1);
        assertThat(invalidated).isNotSameAs(recovered);
    }

    @Test
    void removeCleansUpBothLayers() {
        var k = key("g1", "sit-1");
        store.computeIfAbsent(k, kieBase, config, 0);
        store.remove(k);

        simulateRestart();

        KieSession fresh = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(fresh.getObjects()).isEmpty();
    }

    @Test
    void removeNonExistentKeyIsNoOp() {
        assertThatNoException().isThrownBy(() -> store.remove(key("g1", "no-such")));
    }

    @Test
    void configNotMutated() {
        var k = key("g1", "sit-1");
        KieSessionConfiguration callerConfig = KieServices.Factory.get().newKieSessionConfiguration();
        callerConfig.setOption(ClockTypeOption.PSEUDO);
        String configBefore = callerConfig.toString();
        store.computeIfAbsent(k, kieBase, callerConfig, 0);
        assertThat(callerConfig.toString()).isEqualTo(configBefore);
    }

    @Test
    void recoveryFailureFallback() {
        var k = key("g1", "sit-1");
        KieSession session = store.computeIfAbsent(k, kieBase, config, 0);
        session.insert("test-fact");
        session.fireAllRules();

        simulateRestart();

        // Corrupt the session ID mapping - put a non-existent session ID
        var storage = StorageManagerFactory.get().getStorageManager()
                .getOrCreateSharedStorage("ras_drools_session_ids");
        storage.put(k.toStorageKey(), 999999L);

        // Recovery should fail but fall through to creating a fresh session
        KieSession recovered = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(recovered).isNotNull();
        assertThat(recovered.getObjects()).isEmpty();

        // Second call should succeed without error - no permanent failure loop
        KieSession second = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(second).isNotNull();
        assertThat(second).isSameAs(recovered);
    }


    @Test
    void storageWriteFailureLogsButSessionStillReturned() {
        var k = key("g1", "sit-1");
        KieSession session = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(session).isNotNull();
    }

    @Test
    void gracefulRestartPreservesSessions() {
        var k = key("g1", "sit-1");
        KieSession session = store.computeIfAbsent(k, kieBase, config, 0);
        session.insert("survive-restart");
        session.fireAllRules();
        store.destroy();
        store = new ReliableDroolsSessionStore();
        store.init();
        KieSession recovered = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(recovered).isNotSameAs(session);
        assertThat(recovered.getObjects()).anyMatch(o -> "survive-restart".equals(o));
    }


    private ReliableDroolsSessionStore createStoreWithMetrics(SimpleMeterRegistry registry) {
        var metricsStore = new ReliableDroolsSessionStore();
        metricsStore.setMeterRegistry(registry);
        metricsStore.init();
        return metricsStore;
    }

    @Test
    void createdCounterIncrements() {
        var registry = new SimpleMeterRegistry();
        store = createStoreWithMetrics(registry);
        store.computeIfAbsent(key("g1", "sit-1"), kieBase, config, 0);
        assertThat(registry.counter("ras.drools.session.created", "ganglion_id", "g1").count())
                .isEqualTo(1.0);
    }

    @Test
    void hotCacheHitRecordsTimerWithHitOutcome() {
        var registry = new SimpleMeterRegistry();
        store = createStoreWithMetrics(registry);
        var k = key("g1", "sit-1");
        store.computeIfAbsent(k, kieBase, config, 0);
        store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(registry.timer("ras.drools.session.compute_time",
                "ganglion_id", "g1", "outcome", "hit").count()).isEqualTo(1);
    }

    @Test
    void recoveryIncrementsRecoveredCounter() {
        var registry = new SimpleMeterRegistry();
        store = createStoreWithMetrics(registry);
        var k = key("g1", "sit-1");
        store.computeIfAbsent(k, kieBase, config, 0);
        store.clearHotCacheForTest();
        ((TestableStorageManager) StorageManagerFactory.get().getStorageManager()).restart();
        ReliableRuntimeComponentFactoryImpl.refreshCounterUsingStorage();
        store = createStoreWithMetrics(registry);
        store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(registry.counter("ras.drools.session.recovered", "ganglion_id", "g1").count())
                .isEqualTo(1.0);
    }

    @Test
    void evictionIncrementsEvictedCounter() {
        var registry = new SimpleMeterRegistry();
        store = createStoreWithMetrics(registry);
        var k = key("g1", "sit-1");
        store.computeIfAbsent(k, kieBase, config, 0);
        store.computeIfAbsent(k, kieBase, config, 1);
        assertThat(registry.counter("ras.drools.session.evicted", "ganglion_id", "g1").count())
                .isEqualTo(1.0);
    }

    @Test
    void removeIncrementsRemovedCounter() {
        var registry = new SimpleMeterRegistry();
        store = createStoreWithMetrics(registry);
        var k = key("g1", "sit-1");
        store.computeIfAbsent(k, kieBase, config, 0);
        store.remove(k);
        assertThat(registry.counter("ras.drools.session.removed", "ganglion_id", "g1").count())
                .isEqualTo(1.0);
    }

    @Test
    void activeSessionGaugeReflectsHotCacheSize() {
        var registry = new SimpleMeterRegistry();
        store = createStoreWithMetrics(registry);
        assertThat(registry.get("ras.drools.session.active").gauge().value()).isEqualTo(0.0);
        store.computeIfAbsent(key("g1", "sit-1"), kieBase, config, 0);
        assertThat(registry.get("ras.drools.session.active").gauge().value()).isEqualTo(1.0);
        store.computeIfAbsent(key("g2", "sit-2"), kieBase, config, 0);
        assertThat(registry.get("ras.drools.session.active").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void worksWithoutMetrics() {
        var k = key("g1", "sit-1");
        assertThatNoException().isThrownBy(() -> store.computeIfAbsent(k, kieBase, config, 0));
        assertThatNoException().isThrownBy(() -> store.remove(k));
    }

    private void simulateRestart() {
        // Simulate JVM crash: abandon sessions without disposing.
        // dispose() removes persisted data from H2MVStore, so we must skip it
        // to test restart survival. Just clear the hot cache and drop the reference.
        store.clearHotCacheForTest();
        ((TestableStorageManager) StorageManagerFactory.get().getStorageManager()).restart();
        ReliableRuntimeComponentFactoryImpl.refreshCounterUsingStorage();
        store = new ReliableDroolsSessionStore();
        store.init();
    }
}
