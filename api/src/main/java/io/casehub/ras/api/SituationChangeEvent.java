package io.casehub.ras.api;

import java.util.Objects;

public record SituationChangeEvent(
        String tenancyId,
        String situationId,
        String correlationKey,
        ChangeType changeType,
        SituationContext context
) {
    public enum ChangeType { TRIGGERED, RESOLVED, DISCARDED }

    public SituationChangeEvent {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(changeType, "changeType");
        Objects.requireNonNull(context, "context");
    }
}
