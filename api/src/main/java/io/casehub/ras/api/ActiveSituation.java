package io.casehub.ras.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ActiveSituation(
        String situationId,
        String correlationKey,
        String tenancyId,
        double confidence,
        Map<String, Object> evidence,
        Instant since,
        Instant lastSignal,
        int triggerCount
) {
    public ActiveSituation {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(since, "since");
        Objects.requireNonNull(lastSignal, "lastSignal");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be 0.0-1.0, got: " + confidence);
        }
        evidence = evidence != null ? Map.copyOf(evidence) : Map.of();
    }
}
