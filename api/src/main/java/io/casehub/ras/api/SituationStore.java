package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.Optional;

public interface SituationStore {

    Uni<Optional<SituationContext>> find(String situationId, String tenancyId);

    Uni<Void> save(SituationContext context);

    Uni<Void> remove(String situationId, String tenancyId);

    Uni<Void> removeExpired(Instant cutoff);
}
