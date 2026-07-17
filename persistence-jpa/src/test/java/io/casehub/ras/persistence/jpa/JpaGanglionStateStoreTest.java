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
        jpaStore.removeForSituation("sit-1").await().indefinitely();
        jpaStore.removeForSituation("sit-A").await().indefinitely();
        jpaStore.removeForSituation("sit-B").await().indefinitely();
        jpaStore.removeForSituation("orphan-sit").await().indefinitely();
    }

    @Test
    void loadReturnsStoreVersion() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
                .await().indefinitely();

        var loaded = store.load(key).await().indefinitely().orElseThrow();
        assertThat(loaded.storeVersion()).isPresent();
    }

    @Test
    void saveWithStaleVersionThrowsConflictException() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
                .await().indefinitely();

        var loaded = store.load(key).await().indefinitely().orElseThrow();
        store.save(key, new GanglionState(new double[]{2.0}, loaded.storeVersion()))
                .await().indefinitely();

        assertThatThrownBy(() ->
                store.save(key, new GanglionState(new double[]{3.0}, loaded.storeVersion()))
                        .await().indefinitely())
                .isInstanceOf(GanglionStateConflictException.class);
    }

    @Test
    void removeOrphanedRemovesEntriesWithNoMatchingSituation() {
        var key = new GanglionStateKey("g1", "orphan-sit", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0}, OptionalLong.empty()))
                .await().indefinitely();

        int removed = ((OrphanedResourceCleaner) jpaStore).removeOrphaned().await().indefinitely();

        assertThat(removed).isEqualTo(1);
        assertThat(store.load(key).await().indefinitely()).isEmpty();
    }
}
