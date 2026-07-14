package io.casehub.ras.api;

public record GanglionStateKey(
    String ganglionId,
    String situationId,
    String correlationKey,
    String tenancyId
) {}
