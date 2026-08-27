package io.casehub.ras.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SituationStore {

    Optional<SituationContext> find(String situationId, String correlationKey, String tenancyId);

    SituationContext save(SituationContext context);

    void remove(String situationId, String correlationKey, String tenancyId);

    int removeExpired(Instant cutoff);

    default boolean tryClaimTrigger(String situationId, String correlationKey,
                                    String tenancyId, Instant triggerTime) {
        return true;
    }

    default void resetTriggerClaim(String situationId, String correlationKey,
                                   String tenancyId) {
    }

    default int removeTriggeredBefore(Instant triggerCutoff) {
        return 0;
    }

    default List<SituationContext> findActive(String tenancyId) {
        return List.of();
    }


    default List<SituationContext> findActiveBySituationId(String situationId) {
        return List.of();
    }

    void removeAllForSituation(String situationId);
}

