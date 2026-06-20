package io.casehub.ras.api;

import java.util.Map;
import java.util.Objects;

public record CaseTriggerConfig(
        String caseNamespace,
        String caseName,
        String caseVersion,
        Map<String, Object> baseCaseData
) {
    public CaseTriggerConfig {
        Objects.requireNonNull(caseNamespace, "caseNamespace");
        Objects.requireNonNull(caseName, "caseName");
        Objects.requireNonNull(caseVersion, "caseVersion");
        baseCaseData = baseCaseData != null ? Map.copyOf(baseCaseData) : Map.of();
    }
}
