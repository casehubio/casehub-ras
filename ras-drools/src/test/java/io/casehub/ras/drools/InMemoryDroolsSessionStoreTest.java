package io.casehub.ras.drools;

import org.drools.model.codegen.ExecutableModelProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import static org.assertj.core.api.Assertions.*;

class InMemoryDroolsSessionStoreTest {

    private InMemoryDroolsSessionStore store;
    private KieBase kieBase;
    private KieSessionConfiguration config;

    @BeforeEach
    void setUp() {
        store = new InMemoryDroolsSessionStore();
        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        KieBuilder kb = ks.newKieBuilder(kfs);
        kb.buildAll(ExecutableModelProject.class);
        kieBase = ks.newKieContainer(kb.getKieModule().getReleaseId()).getKieBase();
        config = ks.newKieSessionConfiguration();
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
    void differentKeysAreIndependent() {
        KieSession s1 = store.computeIfAbsent(key("g1", "sit-1"), kieBase, config, 0);
        KieSession s2 = store.computeIfAbsent(key("g2", "sit-1"), kieBase, config, 0);
        assertThat(s2).isNotSameAs(s1);
    }

    @Test
    void generationMismatchDisposesOldAndCreatesNew() {
        var k = key("g1", "sit-1");
        KieSession old = store.computeIfAbsent(k, kieBase, config, 0);
        KieSession fresh = store.computeIfAbsent(k, kieBase, config, 1);
        assertThat(fresh).isNotSameAs(old);
    }

    @Test
    void sameGenerationReturnsCached() {
        var k = key("g1", "sit-1");
        KieSession s1 = store.computeIfAbsent(k, kieBase, config, 5);
        KieSession s2 = store.computeIfAbsent(k, kieBase, config, 5);
        assertThat(s2).isSameAs(s1);
    }

    @Test
    void removeEvictsSession() {
        var k = key("g1", "sit-1");
        store.computeIfAbsent(k, kieBase, config, 0);
        store.remove(k);
        KieSession fresh = store.computeIfAbsent(k, kieBase, config, 0);
        assertThat(fresh).isNotNull();
    }

    @Test
    void removeNonExistentKeyIsNoOp() {
        assertThatNoException().isThrownBy(() -> store.remove(key("g1", "no-such")));
    }

    @Test
    void removeAllScopedToGanglion() {
        var k1 = key("g1", "sit-1");
        var k2 = new DroolsSessionKey("g1", "sit-2", "key-2", "tenant-a");
        var k3 = key("g2", "sit-1");
        KieSession s1 = store.computeIfAbsent(k1, kieBase, config, 0);
        KieSession s2 = store.computeIfAbsent(k2, kieBase, config, 0);
        KieSession s3 = store.computeIfAbsent(k3, kieBase, config, 0);

        store.removeAll("g1");

        // g1 sessions gone — computeIfAbsent creates fresh
        KieSession s1b = store.computeIfAbsent(k1, kieBase, config, 0);
        assertThat(s1b).isNotSameAs(s1);
        // g2 session survives
        KieSession s3b = store.computeIfAbsent(k3, kieBase, config, 0);
        assertThat(s3b).isSameAs(s3);
    }

    @Test
    void removeAllNonExistentGanglionIsNoOp() {
        assertThatNoException().isThrownBy(() -> store.removeAll("no-such"));
    }
}
