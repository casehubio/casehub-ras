package io.casehub.ras.persistence.memory;

import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationStore;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
@Alternative
@Priority(100)
public class InMemorySituationStore implements SituationStore {

    private record SituationKey(String situationId, String correlationKey, String tenancyId) {}

    private final ConcurrentHashMap<SituationKey, SituationContext> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SituationKey, AtomicLong> versions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SituationKey, Boolean> claims = new ConcurrentHashMap<>();

    @Override
    public Uni<Optional<SituationContext>> find(String situationId, String correlationKey, String tenancyId) {
        final var key = new SituationKey(situationId, correlationKey, tenancyId);
        SituationContext ctx = store.get(key);
        if (ctx == null) {
            return Uni.createFrom().item(Optional.empty());
        }
        long version = versions.get(key).get();
        var withVersion = new SituationContext(
                ctx.situationId(), ctx.correlationKey(), ctx.tenancyId(),
                ctx.firstSignal(), ctx.lastSignal(), ctx.detections(),
                OptionalLong.of(version), null, 0);
        return Uni.createFrom().item(Optional.of(withVersion));
    }

    @Override
    public Uni<Void> save(SituationContext context) {
        final var key = new SituationKey(context.situationId(), context.correlationKey(), context.tenancyId());
        versions.computeIfAbsent(key, k -> new AtomicLong(-1L)).incrementAndGet();
        store.put(key, context);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> remove(String situationId, String correlationKey, String tenancyId) {
        final var key = new SituationKey(situationId, correlationKey, tenancyId);
        store.remove(key);
        versions.remove(key);
        claims.remove(key);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> removeExpired(Instant cutoff) {
        store.entrySet().removeIf(entry -> {
            if (!entry.getValue().lastSignal().isAfter(cutoff)) {
                final var key = entry.getKey();
                versions.remove(key);
                claims.remove(key);
                return true;
            }
            return false;
        });
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> tryClaimTrigger(String situationId, String correlationKey, String tenancyId) {
        final var key = new SituationKey(situationId, correlationKey, tenancyId);
        return Uni.createFrom().item(
                claims.putIfAbsent(key, Boolean.TRUE) == null);
    }

    @Override
    public Uni<Void> resetTriggerClaim(String situationId, String correlationKey, String tenancyId) {
        claims.remove(new SituationKey(situationId, correlationKey, tenancyId));
        return Uni.createFrom().voidItem();
    }
}
