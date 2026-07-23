package io.casehub.ras.api;

import java.util.UUID;

public interface CaseTrigger {
    UUID fire(CaseTriggerConfig triggerConfig, SituationContext context);
}
