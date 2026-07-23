package io.casehub.ras.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

public abstract class AbstractGanglionStateStoreContractTest {

    protected GanglionStateStore store;

    protected abstract GanglionStateStore createStore();

    @BeforeEach
    void setUpStore() {
        store = createStore();
    }

    @Test
    void loadAbsentKeyReturnsEmpty() {
        var key    = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        var result = store.load(key);
        assertThat(result).isEmpty();
    }

    @Test
    void saveAndLoadRoundTrip() {
        var key   = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        var state = new GanglionState(new double[]{-0.105, -2.303}, OptionalLong.empty());
        store.save(key, state);

        var loaded = store.load(key);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().values()).containsExactly(-0.105, -2.303);
    }

    @Test
    void saveOverwritesPreviousValue() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0}, OptionalLong.empty()));

        var loaded1 = store.load(key).orElseThrow();
        store.save(key, new GanglionState(new double[]{2.0}, loaded1.storeVersion()));

        var loaded2 = store.load(key);
        assertThat(loaded2).isPresent();
        assertThat(loaded2.get().values()).containsExactly(2.0);
    }

    @Test
    void removeThenLoadReturnsEmpty() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0}, OptionalLong.empty()));
        store.remove(key);

        assertThat(store.load(key)).isEmpty();
    }

    @Test
    void removeNonExistentIsNoOp() {
        var key = new GanglionStateKey("g1", "nonexistent", "k", "t");
        assertThatNoException().isThrownBy(
                () -> store.remove(key));
    }

    @Test
    void removeForSituationRemovesOnlyMatchingSituationId() {
        var keyA = new GanglionStateKey("g1", "sit-A", "key-1", "tenant-a");
        var keyB = new GanglionStateKey("g2", "sit-A", "key-2", "tenant-a");
        var keyC = new GanglionStateKey("g1", "sit-B", "key-1", "tenant-a");

        store.save(keyA, new GanglionState(new double[]{1.0}, OptionalLong.empty()));
        store.save(keyB, new GanglionState(new double[]{2.0}, OptionalLong.empty()));
        store.save(keyC, new GanglionState(new double[]{3.0}, OptionalLong.empty()));

        store.removeForSituation("sit-A");

        assertThat(store.load(keyA)).isEmpty();
        assertThat(store.load(keyB)).isEmpty();
        assertThat(store.load(keyC)).isPresent();
    }

    @Test
    void removeForSituationNoopWhenNoMatches() {
        assertThatNoException().isThrownBy(
                () -> store.removeForSituation("nonexistent"));
    }

    @Test
    void defensiveCopyOnSave() {
        var      key      = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        double[] original = {1.0, 2.0};
        store.save(key, new GanglionState(original, OptionalLong.empty()));

        original[0] = 999.0;

        var loaded = store.load(key).orElseThrow();
        assertThat(loaded.values()[0]).isEqualTo(1.0);
    }

    @Test
    void defensiveCopyOnLoad() {
        var key = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        store.save(key, new GanglionState(new double[]{1.0, 2.0}, OptionalLong.empty()));

        var loaded = store.load(key).orElseThrow();
        loaded.values()[0] = 999.0;

        var reloaded = store.load(key).orElseThrow();
        assertThat(reloaded.values()[0]).isEqualTo(1.0);
    }

    @Test
    void isolationByGanglionId() {
        var key1 = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        var key2 = new GanglionStateKey("g2", "sit-1", "key-1", "tenant-a");

        store.save(key1, new GanglionState(new double[]{1.0}, OptionalLong.empty()));
        store.save(key2, new GanglionState(new double[]{2.0}, OptionalLong.empty()));

        assertThat(store.load(key1).orElseThrow().values())
                .containsExactly(1.0);
        assertThat(store.load(key2).orElseThrow().values())
                .containsExactly(2.0);
    }

    @Test
    void isolationByTenancyId() {
        var key1 = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-a");
        var key2 = new GanglionStateKey("g1", "sit-1", "key-1", "tenant-b");

        store.save(key1, new GanglionState(new double[]{1.0}, OptionalLong.empty()));
        store.save(key2, new GanglionState(new double[]{2.0}, OptionalLong.empty()));

        assertThat(store.load(key1).orElseThrow().values())
                .containsExactly(1.0);
        assertThat(store.load(key2).orElseThrow().values())
                .containsExactly(2.0);
    }
}
