package io.casehub.ras.memory;

import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationStore;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@Alternative
@Priority(1)
public class InMemorySituationStore implements SituationStore {

    private record SituationKey(String situationId, String correlationKey, String tenancyId) {}

    private final ConcurrentHashMap<SituationKey, SituationContext> store = new ConcurrentHashMap<>();

    @Override
    public Uni<Optional<SituationContext>> find(String situationId, String correlationKey, String tenancyId) {
        return Uni.createFrom().item(Optional.ofNullable(store.get(new SituationKey(situationId, correlationKey, tenancyId))));
    }

    @Override
    public Uni<Void> save(SituationContext context) {
        store.put(new SituationKey(context.situationId(), context.correlationKey(), context.tenancyId()), context);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> remove(String situationId, String correlationKey, String tenancyId) {
        store.remove(new SituationKey(situationId, correlationKey, tenancyId));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> removeExpired(Instant cutoff) {
        store.entrySet().removeIf(entry -> !entry.getValue().lastSignal().isAfter(cutoff));
        return Uni.createFrom().voidItem();
    }
}
