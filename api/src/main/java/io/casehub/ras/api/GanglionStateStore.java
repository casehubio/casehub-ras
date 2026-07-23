package io.casehub.ras.api;

import java.util.Optional;

public interface GanglionStateStore {
    Optional<GanglionState> load(GanglionStateKey key);

    void save(GanglionStateKey key, GanglionState state);

    void remove(GanglionStateKey key);

    void removeForSituation(String situationId);
}
