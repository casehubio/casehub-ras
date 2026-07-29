package io.casehub.ras.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record SituationEvent(
        String situationId,
        String correlationKey,
        String tenancyId,
        SituationChangeEvent.ChangeType changeType,
        Instant eventTime,
        Instant firstSeen,
        double confidence,
        int detectionCount,
        int triggerCount,
        Map<String, Object> evidence,
        Map<String, Object> metadata
) {
    public SituationEvent {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(changeType, "changeType");
        Objects.requireNonNull(eventTime, "eventTime");
        Objects.requireNonNull(firstSeen, "firstSeen");
        evidence = evidence != null ? Map.copyOf(evidence) : Map.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static SituationEvent from(SituationChangeEvent changeEvent, Instant eventTime) {
        SituationContext ctx = changeEvent.context();
        java.util.OptionalDouble maxQualifying = ctx.detections().stream()
                                                    .filter(td -> td.result().signal().isAtLeast(DetectionSignal.WEAK))
                                                    .mapToDouble(td -> td.result().confidence())
                                                    .max();
        double confidence = maxQualifying.orElse(0.0);

        Map<String, Object> evidence = ctx.detections().stream()
                                          .filter(td -> td.result().signal().isAtLeast(DetectionSignal.WEAK))
                                          .filter(td -> td.result().confidence() == confidence)
                                          .findFirst()
                                          .map(td -> td.result().evidence())
                                          .orElse(Map.of());

        return new SituationEvent(
                ctx.situationId(), ctx.correlationKey(), ctx.tenancyId(),
                changeEvent.changeType(), eventTime, ctx.firstSignal(),
                confidence, ctx.detections().size(), ctx.triggerCount(),
                evidence, changeEvent.metadata());
    }
}
