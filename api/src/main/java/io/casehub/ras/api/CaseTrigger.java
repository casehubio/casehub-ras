package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;
import java.util.UUID;

public interface CaseTrigger {
    Uni<UUID> fire(CaseTriggerConfig triggerConfig, SituationContext context);
}
