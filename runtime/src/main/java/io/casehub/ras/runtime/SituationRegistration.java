package io.casehub.ras.runtime;

import io.casehub.ras.api.SituationDefinition;
import java.util.Objects;

public record SituationRegistration(
        SituationDefinition definition,
        CorrelationKeyExtractor correlationKeyExtractor
) {
    public SituationRegistration {
        Objects.requireNonNull(definition, "definition");
        if (correlationKeyExtractor == null) {
            correlationKeyExtractor = DefaultCorrelationKeyExtractor.INSTANCE;
        }
    }

    public SituationRegistration(SituationDefinition definition) {
        this(definition, null);
    }
}
