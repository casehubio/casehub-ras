package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SituationStore {

    Uni<Optional<SituationContext>> find(String situationId, String correlationKey, String tenancyId);

    Uni<SituationContext> save(SituationContext context);

    Uni<Void> remove(String situationId, String correlationKey, String tenancyId);

    Uni<Void> removeExpired(Instant cutoff);

    default Uni<Boolean> tryClaimTrigger(String situationId, String correlationKey,
                                          String tenancyId, Instant triggerTime) {
        return Uni.createFrom().item(true);
    }

    default Uni<Void> resetTriggerClaim(String situationId, String correlationKey,
                                         String tenancyId) {
        return Uni.createFrom().voidItem();
    }

    default Uni<Void> removeTriggeredBefore(Instant triggerCutoff) {
        return Uni.createFrom().voidItem();
    }

    default Uni<List<SituationContext>> findActive(String tenancyId) {
        return Uni.createFrom().item(List.of());
    }

    Uni<Void> removeAllForSituation(String situationId);
}
