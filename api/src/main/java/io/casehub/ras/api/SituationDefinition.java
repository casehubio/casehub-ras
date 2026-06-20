package io.casehub.ras.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public record SituationDefinition(
        String situationId,
        Set<String> eventTypes,
        Duration correlationWindow,
        ChainMode chainMode,
        CaseTriggerConfig triggerConfig
) {
    public SituationDefinition {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(chainMode, "chainMode");
        Objects.requireNonNull(triggerConfig, "triggerConfig");
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new IllegalArgumentException("eventTypes must not be empty");
        }
        eventTypes = Set.copyOf(eventTypes);
        if (correlationWindow != null
                && (correlationWindow.isZero() || correlationWindow.isNegative())) {
            throw new IllegalArgumentException(
                    "correlationWindow must be positive when set, got: " + correlationWindow);
        }
    }
}
