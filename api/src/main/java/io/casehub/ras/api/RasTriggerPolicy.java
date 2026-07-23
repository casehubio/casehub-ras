package io.casehub.ras.api;

public interface RasTriggerPolicy {
    PolicyDecision evaluate(SituationContext context, SituationDefinition definition);
}
