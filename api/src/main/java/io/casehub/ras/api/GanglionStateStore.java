package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;
import java.util.Optional;

public interface GanglionStateStore {
    Uni<Optional<GanglionState>> load(GanglionStateKey key);
    Uni<Void> save(GanglionStateKey key, GanglionState state);
    Uni<Void> remove(GanglionStateKey key);
    Uni<Void> removeForSituation(String situationId);
}
