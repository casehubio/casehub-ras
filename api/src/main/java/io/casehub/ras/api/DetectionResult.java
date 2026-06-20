package io.casehub.ras.api;

import java.util.Map;
import java.util.Objects;

public record DetectionResult(
        String ganglionId,
        double confidence,
        DetectionSignal signal,
        Map<String, Object> evidence
) {
    public DetectionResult {
        Objects.requireNonNull(ganglionId, "ganglionId");
        Objects.requireNonNull(signal, "signal");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be 0.0–1.0, got: " + confidence);
        }
        evidence = evidence != null ? Map.copyOf(evidence) : Map.of();
    }
}
