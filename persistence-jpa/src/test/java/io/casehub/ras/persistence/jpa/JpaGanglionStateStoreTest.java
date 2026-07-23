package io.casehub.ras.persistence.jpa;

import io.casehub.ras.api.AbstractGanglionStateStoreContractTest;
import io.casehub.ras.api.GanglionState;
import io.casehub.ras.api.GanglionStateConflictException;
import io.casehub.ras.api.GanglionStateKey;
import io.casehub.ras.api.GanglionStateStore;
import io.casehub.ras.api.OrphanedResourceCleaner;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class JpaGanglionStateStoreTest extends AbstractGanglionStateStoreContractTest {

    @Inject
    JpaGanglionStateStore jpaStore;

    @Override
    protected GanglionStateStore createStore() {
        return jpaStore;
    }

    @BeforeEach
    @Transactional
    void cleanTable() {
        jpaStore.removeForSituation("sit-1");
        jpaStore.removeForSituation("sit-A");
        jpaStore.removeForSituation("sit-B");
        jpaStore.removeForSituation("orphan-sit");
    }

    @Test
    void loadReturnsStoreVersion() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
                ;

        var loaded = store.load(key).orElseThrow();
        assertThat(loaded.storeVersion()).isPresent();
    }

    @Test
    void saveWithStaleVersionThrowsConflictException() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
                ;

        var loaded = store.load(key).orElseThrow();
        store.save(key, new GanglionState(new double[]{2.0}, loaded.storeVersion()))
                ;

        assertThatThrownBy(() ->
                store.save(key, new GanglionState(new double[]{3.0}, loaded.storeVersion()))
                        )
                .isInstanceOf(GanglionStateConflictException.class);
    }

    @Test
    void removeOrphanedRemovesEntriesWithNoMatchingSituation() {
        var key = new GanglionStateKey("g1", "orphan-sit", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
                ;

        int removed = ((OrphanedResourceCleaner) jpaStore).removeOrphaned();

        assertThat(removed).isEqualTo(1);
        assertThat(store.load(key)).isEmpty();
    }
}
