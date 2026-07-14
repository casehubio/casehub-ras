package io.casehub.ras.runtime;

import io.casehub.ras.api.GanglionState;
import io.casehub.ras.api.GanglionStateKey;
import io.casehub.ras.api.GanglionStateStore;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
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
    public Uni<Optional<GanglionState>> load(GanglionStateKey key) {
        double[] values = store.get(key);
        if (values == null) {
            return Uni.createFrom().item(Optional.empty());
        }
        return Uni.createFrom().item(Optional.of(
                new GanglionState(Arrays.copyOf(values, values.length), OptionalLong.empty())));
    }

    @Override
    public Uni<Void> save(GanglionStateKey key, GanglionState state) {
        store.put(key, Arrays.copyOf(state.values(), state.values().length));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> remove(GanglionStateKey key) {
        store.remove(key);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> removeForSituation(String situationId) {
        store.keySet().removeIf(key -> key.situationId().equals(situationId));
        return Uni.createFrom().voidItem();
    }
}
