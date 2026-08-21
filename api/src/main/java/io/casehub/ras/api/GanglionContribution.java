package io.casehub.ras.api;

import java.util.Objects;

public record GanglionContribution(
        String ganglionId,
        double confidence,
        DetectionSignal signal
) {
    public GanglionContribution {
        Objects.requireNonNull(ganglionId, "ganglionId");
        Objects.requireNonNull(signal, "signal");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be 0.0-1.0, got: " + confidence);
        }
    }
}
