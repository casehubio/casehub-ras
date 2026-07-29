package io.casehub.ras.api;

import java.time.Instant;
import java.util.Objects;

public record SituationSummary(
        String situationId,
        long eventCount,
        long triggerCount,
        Instant lastEvent
) {
    public SituationSummary {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(lastEvent, "lastEvent");
    }
}
