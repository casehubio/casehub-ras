package io.casehub.ras.api;

import java.util.Map;
import java.util.Objects;

public record SituationChangeEvent(
        String tenancyId,
        String situationId,
        String correlationKey,
        ChangeType changeType,
        SituationContext context,
        Map<String, Object> metadata) {

    public enum ChangeType {TRIGGERED, RESOLVED, DISCARDED, SUPPRESSED, DISMISSED}

    public SituationChangeEvent {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(changeType, "changeType");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(metadata, "metadata");
    }

    public SituationChangeEvent(String tenancyId, String situationId,
                                String correlationKey, ChangeType changeType, SituationContext context) {
        this(tenancyId, situationId, correlationKey, changeType, context, Map.of());
    }
}
