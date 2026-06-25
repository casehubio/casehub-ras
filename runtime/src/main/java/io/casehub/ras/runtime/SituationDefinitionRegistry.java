package io.casehub.ras.runtime;

import io.casehub.ras.api.Ganglion;
import io.casehub.ras.api.SituationDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Comparator;
import java.util.Objects;

@ApplicationScoped
public class SituationDefinitionRegistry {

    private final Map<String, List<SituationRegistration>> byEventType;
    private final Map<String, Ganglion> gangliaById;
    private final Duration maxCorrelationWindow;

    @Inject
    public SituationDefinitionRegistry(Instance<SituationDefinitionProvider> providers,
                                       Instance<Ganglion> ganglia) {
        this(toList(providers), toList(ganglia));
    }

    SituationDefinitionRegistry(List<SituationDefinitionProvider> providers,
                                List<Ganglion> ganglia) {
        this.gangliaById = ganglia.stream()
                .collect(Collectors.toMap(
                        Ganglion::ganglionId,
                        g -> g,
                        (g1, g2) -> {
                            throw new IllegalStateException(
                                    "Duplicate ganglionId '" + g1.ganglionId()
                                    + "' — found in " + g1.getClass().getName()
                                    + " and " + g2.getClass().getName());
                        }));

        List<SituationRegistration> allRegistrations = new ArrayList<>();
        Set<String> seenSituationIds = new HashSet<>();
        for (var provider : providers) {
            for (var reg : provider.registrations()) {
                String sitId = reg.definition().situationId();
                if (!seenSituationIds.add(sitId)) {
                    throw new IllegalStateException(
                            "Duplicate situationId '" + sitId + "' across providers");
                }
                validate(reg.definition());
                allRegistrations.add(reg);
            }
        }

        Map<String, List<SituationRegistration>> index = new HashMap<>();
        for (var reg : allRegistrations) {
            for (String eventType : reg.definition().eventTypes()) {
                index.computeIfAbsent(eventType, k -> new ArrayList<>()).add(reg);
            }
        }
        this.byEventType = Map.copyOf(
                index.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue()))));

        this.maxCorrelationWindow = allRegistrations.stream()
                .map(r -> r.definition().correlationWindow())
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    public List<SituationRegistration> findByEventType(String eventType) {
        return byEventType.getOrDefault(eventType, List.of());
    }

    public Ganglion ganglion(String ganglionId) {
        Ganglion g = gangliaById.get(ganglionId);
        if (g == null) {
            throw new IllegalArgumentException("Unknown ganglionId: " + ganglionId);
        }
        return g;
    }

    public Duration maxCorrelationWindow() {
        return maxCorrelationWindow;
    }

    private void validate(SituationDefinition def) {
        for (String ganglionId : def.chainMode().referencedGanglia()) {
            Ganglion g = gangliaById.get(ganglionId);
            if (g == null) {
                throw new IllegalStateException(
                        "Situation '" + def.situationId() + "' references unknown ganglion '" + ganglionId + "'");
            }
            Set<String> overlap = new HashSet<>(g.handledEventTypes());
            overlap.retainAll(def.eventTypes());
            if (overlap.isEmpty()) {
                throw new IllegalStateException(
                        "Ganglion '" + ganglionId + "' handles " + g.handledEventTypes()
                        + " but situation '" + def.situationId() + "' declares " + def.eventTypes()
                        + " — no overlap");
            }
        }
    }

    private static <T> List<T> toList(Instance<T> instance) {
        List<T> list = new ArrayList<>();
        instance.forEach(list::add);
        return list;
    }
}
