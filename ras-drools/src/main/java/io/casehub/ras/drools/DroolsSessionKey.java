package io.casehub.ras.drools;

public record DroolsSessionKey(
    String ganglionId,
    String situationId,
    String correlationKey,
    String tenancyId
) {
    public String toStorageKey() {
        return ganglionId + "|" + situationId + "|" + correlationKey + "|" + tenancyId;
    }
}
