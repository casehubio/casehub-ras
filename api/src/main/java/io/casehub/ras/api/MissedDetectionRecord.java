package io.casehub.ras.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MissedDetectionRecord(
        String situationId,
        String correlationKey,
        String tenancyId,
        Instant eventTime,
        String reportedBy,
        UUID reportId,
        Instant recordedAt
) {
    public MissedDetectionRecord {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(eventTime, "eventTime");
        Objects.requireNonNull(reportedBy, "reportedBy");
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
