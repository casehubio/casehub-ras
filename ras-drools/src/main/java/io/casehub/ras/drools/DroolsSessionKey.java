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

    public static DroolsSessionKey fromStorageKey(String storageKey) {
        String[] parts = storageKey.split("\\|", 4);
        if (parts.length < 4) {
            throw new IllegalArgumentException(
                    "Malformed storage key (expected 4 '|'-separated parts): " + storageKey);
        }
        return new DroolsSessionKey(parts[0], parts[1], parts[2], parts[3]);
    }

}
