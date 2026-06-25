package io.casehub.ras.api;

import java.time.Instant;
import java.util.Objects;

public record TimestampedDetection(DetectionResult result, Instant eventTime) {
    public TimestampedDetection {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(eventTime, "eventTime");
    }
}
