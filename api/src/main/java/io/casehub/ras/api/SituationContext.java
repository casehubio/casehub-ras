package io.casehub.ras.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record SituationContext(
        String situationId,
        String tenancyId,
        Instant firstSignal,
        Instant lastSignal,
        List<DetectionResult> detections
) {
    public SituationContext {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(firstSignal, "firstSignal");
        Objects.requireNonNull(lastSignal, "lastSignal");
        detections = detections != null ? List.copyOf(detections) : List.of();
    }

    public static SituationContext initial(String situationId, String tenancyId, Instant eventTime) {
        Objects.requireNonNull(eventTime, "eventTime");
        return new SituationContext(situationId, tenancyId, eventTime, eventTime, List.of());
    }

    public SituationContext withDetection(DetectionResult result, Instant eventTime) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(eventTime, "eventTime");
        var newDetections = new ArrayList<>(detections);
        newDetections.add(result);
        Instant newFirst = eventTime.isBefore(firstSignal) ? eventTime : firstSignal;
        Instant newLast = eventTime.isAfter(lastSignal) ? eventTime : lastSignal;
        return new SituationContext(situationId, tenancyId, newFirst, newLast, newDetections);
    }
}
