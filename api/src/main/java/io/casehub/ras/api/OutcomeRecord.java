package io.casehub.ras.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OutcomeRecord(
        String situationId,
        String correlationKey,
        String tenancyId,
        String outcomeLabel,
        OutcomeClassification classification,
        Instant closedAt,
        UUID caseId
) {
    public OutcomeRecord {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(outcomeLabel, "outcomeLabel");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(closedAt, "closedAt");
        Objects.requireNonNull(caseId, "caseId");
    }
}
