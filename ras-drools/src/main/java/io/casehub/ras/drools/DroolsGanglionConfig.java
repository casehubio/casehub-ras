package io.casehub.ras.drools;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record DroolsGanglionConfig(
        String ganglionId,
        Set<String> handledEventTypes,
        SessionMode sessionMode,
        ClockMode clockMode,
        List<String> classpathRules,
        List<String> programmaticRules
) {
    public DroolsGanglionConfig {
        Objects.requireNonNull(ganglionId);
        Objects.requireNonNull(sessionMode, "sessionMode");
        Objects.requireNonNull(clockMode, "clockMode");
        if (handledEventTypes == null || handledEventTypes.isEmpty()) {
            throw new IllegalArgumentException("handledEventTypes must not be empty");
        }
        handledEventTypes = Set.copyOf(handledEventTypes);
        if (classpathRules == null) classpathRules = List.of();
        if (programmaticRules == null) programmaticRules = List.of();
        classpathRules = List.copyOf(classpathRules);
        programmaticRules = List.copyOf(programmaticRules);
        if (classpathRules.isEmpty() && programmaticRules.isEmpty()) {
            throw new IllegalArgumentException("At least one rule source required");
        }
    }
}
