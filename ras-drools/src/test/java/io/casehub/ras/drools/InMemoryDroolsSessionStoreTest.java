package io.casehub.ras.drools;

import org.drools.model.codegen.ExecutableModelProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.runtime.KieSession;
import static org.assertj.core.api.Assertions.*;

class InMemoryDroolsSessionStoreTest {

    private InMemoryDroolsSessionStore store;
    private KieBase kieBase;

    @BeforeEach
    void setUp() {
        store = new InMemoryDroolsSessionStore();
        KieServices kieServices = KieServices.Factory.get();
        KieFileSystem kfs = kieServices.newKieFileSystem();
        KieBuilder kieBuilder = kieServices.newKieBuilder(kfs);
        kieBuilder.buildAll(ExecutableModelProject.class);
        kieBase = kieServices.newKieContainer(kieBuilder.getKieModule().getReleaseId()).getKieBase();
    }

    private KieSession freshSession() {
        return kieBase.newKieSession();
    }

    @Test
    void getReturnsEmptyForUnknownKey() {
        assertThat(store.get("g1", "sit-1", "tenant-a")).isEmpty();
    }

    @Test
    void putThenGetReturnsSameSession() {
        var session = freshSession();
        store.put("g1", "sit-1", "tenant-a", session);
        assertThat(store.get("g1", "sit-1", "tenant-a")).containsSame(session);
    }

    @Test
    void differentGanglionIdsSameKeysAreIndependent() {
        var session1 = freshSession();
        var session2 = freshSession();
        store.put("g1", "sit-1", "tenant-a", session1);
        store.put("g2", "sit-1", "tenant-a", session2);
        assertThat(store.get("g1", "sit-1", "tenant-a")).containsSame(session1);
        assertThat(store.get("g2", "sit-1", "tenant-a")).containsSame(session2);
    }

    @Test
    void removeDisposesAndEvictsSession() {
        var session = freshSession();
        store.put("g1", "sit-1", "tenant-a", session);
        store.remove("g1", "sit-1", "tenant-a");
        assertThat(store.get("g1", "sit-1", "tenant-a")).isEmpty();
    }

    @Test
    void removeNonExistentKeyIsNoOp() {
        assertThatNoException().isThrownBy(
                () -> store.remove("g1", "no-such", "tenant-a"));
    }

    @Test
    void putUpsertDisposesOldSession() {
        var session1 = freshSession();
        var session2 = freshSession();
        store.put("g1", "sit-1", "tenant-a", session1);
        store.put("g1", "sit-1", "tenant-a", session2);
        assertThat(store.get("g1", "sit-1", "tenant-a")).containsSame(session2);
    }
}
