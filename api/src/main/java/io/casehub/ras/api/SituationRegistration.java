package io.casehub.ras.api;

import io.casehub.platform.api.expression.CompiledExpression;

import java.util.Map;
import java.util.Objects;

public record SituationRegistration(
        SituationDefinition definition,
        CorrelationKeyExtractor correlationKeyExtractor,
        EventFilter eventFilter,
        Map<String, CompiledExpression<Map, Object>> compiledDynamicData
) {
    public SituationRegistration {
        Objects.requireNonNull(definition, "definition");
        if (correlationKeyExtractor == null) {
            correlationKeyExtractor = DefaultCorrelationKeyExtractor.INSTANCE;
        }
    }

    public SituationRegistration(SituationDefinition definition,
                                 CorrelationKeyExtractor correlationKeyExtractor) {
        this(definition, correlationKeyExtractor, null, null);
    }

    public SituationRegistration(SituationDefinition definition) {
        this(definition, null, null, null);
    }
}
