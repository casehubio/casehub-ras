package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;

public interface RasTriggerPolicy {
    Uni<PolicyDecision> evaluate(SituationContext context, SituationDefinition definition);
}
