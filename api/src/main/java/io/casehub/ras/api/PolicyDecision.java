package io.casehub.ras.api;

import java.util.Map;
import java.util.Objects;

public record PolicyDecision(TriggerDecision decision, Map<String, Object> metadata) {

    public PolicyDecision {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(metadata, "metadata");
        metadata = Map.copyOf(metadata);
    }

    public PolicyDecision(TriggerDecision decision) {
        this(decision, Map.of());
    }
}
