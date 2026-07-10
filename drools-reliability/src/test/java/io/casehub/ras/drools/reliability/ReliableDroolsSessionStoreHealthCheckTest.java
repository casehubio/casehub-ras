package io.casehub.ras.drools.reliability;

import io.casehub.ras.drools.DroolsSessionKey;
import org.drools.model.codegen.ExecutableModelProject;
import org.drools.reliability.core.StorageManagerFactory;
import org.drools.reliability.core.TestableStorageManager;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import static org.assertj.core.api.Assertions.*;

class ReliableDroolsSessionStoreHealthCheckTest {

    private ReliableDroolsSessionStore store;
    private ReliableDroolsSessionStoreHealthCheck healthCheck;
    private KieBase kieBase;
    private KieSessionConfiguration config;

    @BeforeEach
    void setUp() {
        StorageManagerFactory.get("h2mvstore").getStorageManager().removeAllSessionStorages();
        store = new ReliableDroolsSessionStore();
        store.init();
        healthCheck = new ReliableDroolsSessionStoreHealthCheck(store);
        KieServices ks = KieServices.Factory.get();
        var kfs = ks.newKieFileSystem();
        var kb = ks.newKieBuilder(kfs).buildAll(ExecutableModelProject.class);
        var kbc = ks.newKieBaseConfiguration();
        kbc.setOption(EventProcessingOption.STREAM);
        kieBase = ks.newKieContainer(kb.getKieModule().getReleaseId()).newKieBase(kbc);
        config = ks.newKieSessionConfiguration();
        config.setOption(ClockTypeOption.PSEUDO);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.destroy();
        ((TestableStorageManager) StorageManagerFactory.get().getStorageManager()).restartWithCleanUp();
    }

    @Test
    void reportsUpWhenHealthy() {
        var response = healthCheck.call();
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData().get()).containsEntry("store", "h2mvstore");
        assertThat(response.getData().get()).containsEntry("activeSessions", 0L);
    }

    @Test
    void reportsActiveSessionCount() {
        store.computeIfAbsent(
                new DroolsSessionKey("g1", "sit-1", "key-1", "tenant-a"),
                kieBase, config, 0);
        store.computeIfAbsent(
                new DroolsSessionKey("g2", "sit-2", "key-2", "tenant-a"),
                kieBase, config, 0);

        var response = healthCheck.call();
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData().get()).containsEntry("activeSessions", 2L);
    }

    @Test
    void reportsDownWhenStorageFails() {
        StorageManagerFactory.get().getStorageManager().close();

        var response = healthCheck.call();
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData().get()).containsKey("error");
    }
}
