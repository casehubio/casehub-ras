package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
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

    private record RegistrySnapshot(
        Map<String, List<SituationRegistration>> byEventType,
        Set<String> situationIds,
        Duration maxCorrelationWindow
    ) {}

    private volatile RegistrySnapshot snapshot;
    private final Map<String, Ganglion> gangliaById;

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

        this.snapshot = buildSnapshot(allRegistrations);
    }

    public List<SituationRegistration> findByEventType(String eventType) {
        return snapshot.byEventType().getOrDefault(eventType, List.of());
    }

    public Ganglion ganglion(String ganglionId) {
        Ganglion g = gangliaById.get(ganglionId);
        if (g == null) {
            throw new IllegalArgumentException("Unknown ganglionId: " + ganglionId);
        }
        return g;
    }

    public Duration maxCorrelationWindow() {
        return snapshot.maxCorrelationWindow();
    }

    public synchronized void register(SituationRegistration registration) {
        String sitId = registration.definition().situationId();
        if (snapshot.situationIds().contains(sitId)) {
            throw new IllegalStateException("Duplicate situationId: " + sitId);
        }
        validate(registration.definition());

        List<SituationRegistration> all = new ArrayList<>();
        snapshot.byEventType().values().stream()
                .flatMap(List::stream)
                .distinct()
                .forEach(all::add);
        all.add(registration);
        this.snapshot = buildSnapshot(all);
    }

    public synchronized void deregister(String situationId) {
        if (!snapshot.situationIds().contains(situationId)) {
            return;
        }
        List<SituationRegistration> remaining = snapshot.byEventType().values().stream()
                .flatMap(List::stream)
                .distinct()
                .filter(reg -> !reg.definition().situationId().equals(situationId))
                .toList();
        this.snapshot = buildSnapshot(remaining);
    }

    private static RegistrySnapshot buildSnapshot(List<SituationRegistration> registrations) {
        Map<String, List<SituationRegistration>> index = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (var reg : registrations) {
            ids.add(reg.definition().situationId());
            for (String eventType : reg.definition().eventTypes()) {
                index.computeIfAbsent(eventType, k -> new ArrayList<>()).add(reg);
            }
        }
        Duration maxWindow = registrations.stream()
                .map(r -> r.definition().correlationWindow())
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        return new RegistrySnapshot(
                Map.copyOf(index.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())))),
                Set.copyOf(ids),
                maxWindow);
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
