package io.casehub.ras.runtime;

import io.casehub.ras.api.GanglionState;
import io.casehub.ras.api.GanglionStateKey;
import io.casehub.ras.api.GanglionStateStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@DefaultBean
public class InMemoryGanglionStateStore implements GanglionStateStore {

    private final ConcurrentHashMap<GanglionStateKey, double[]> store = new ConcurrentHashMap<>();

    @Override
    public Optional<GanglionState> load(GanglionStateKey key) {
        double[] values = store.get(key);
        if (values == null) {
            return Optional.empty();
        }
        return Optional.of(
                new GanglionState(Arrays.copyOf(values, values.length), OptionalLong.empty()));
    }

    @Override
    public void save(GanglionStateKey key, GanglionState state) {
        store.put(key, Arrays.copyOf(state.values(), state.values().length));
    }

    @Override
    public void remove(GanglionStateKey key) {
        store.remove(key);
    }

    @Override
    public void removeForSituation(String situationId) {
        store.keySet().removeIf(key -> key.situationId().equals(situationId));
    }
}
