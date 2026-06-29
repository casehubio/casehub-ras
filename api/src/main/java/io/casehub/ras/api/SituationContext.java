package io.casehub.ras.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

public record SituationContext(
        String situationId,
        String correlationKey,
        String tenancyId,
        Instant firstSignal,
        Instant lastSignal,
        List<TimestampedDetection> detections,
        OptionalLong storeVersion
) {
    public SituationContext {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(firstSignal, "firstSignal");
        Objects.requireNonNull(lastSignal, "lastSignal");
        Objects.requireNonNull(storeVersion, "storeVersion");
        detections = detections != null ? List.copyOf(detections) : List.of();
    }

    public static SituationContext initial(String situationId, String correlationKey,
                                           String tenancyId, Instant eventTime) {
        Objects.requireNonNull(eventTime, "eventTime");
        return new SituationContext(situationId, correlationKey, tenancyId,
                                   eventTime, eventTime, List.of(), OptionalLong.empty());
    }

    public SituationContext withDetection(DetectionResult result, Instant eventTime) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(eventTime, "eventTime");
        var td = new TimestampedDetection(result, eventTime);
        var newDetections = new ArrayList<>(detections);
        newDetections.add(td);
        Instant newFirst = eventTime.isBefore(firstSignal) ? eventTime : firstSignal;
        Instant newLast = eventTime.isAfter(lastSignal) ? eventTime : lastSignal;
        return new SituationContext(situationId, correlationKey, tenancyId,
                                   newFirst, newLast, newDetections, storeVersion);
    }
}
