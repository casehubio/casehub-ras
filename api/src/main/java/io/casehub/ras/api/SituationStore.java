package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.Optional;

public interface SituationStore {

    Uni<Optional<SituationContext>> find(String situationId, String correlationKey, String tenancyId);

    Uni<Void> save(SituationContext context);

    Uni<Void> remove(String situationId, String correlationKey, String tenancyId);

    Uni<Void> removeExpired(Instant cutoff);

    default Uni<Boolean> tryClaimTrigger(String situationId, String correlationKey,
                                          String tenancyId) {
        return Uni.createFrom().item(true);
    }

    default Uni<Void> resetTriggerClaim(String situationId, String correlationKey,
                                         String tenancyId) {
        return Uni.createFrom().voidItem();
    }
}
