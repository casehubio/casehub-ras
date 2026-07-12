package io.casehub.ras.persistence.memory;

import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationStore;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
                OptionalLong.of(version), ctx.lastTriggered(), ctx.triggerCount());
        return Uni.createFrom().item(Optional.of(withVersion));
    }

    @Override
    public Uni<SituationContext> save(SituationContext context) {
        final var key = new SituationKey(context.situationId(), context.correlationKey(), context.tenancyId());
        long newVersion = versions.computeIfAbsent(key, k -> new AtomicLong(-1L)).incrementAndGet();

        // On update (existing key), preserve store-managed trigger metadata
        SituationContext toStore = context;
        SituationContext existing = store.get(key);
        if (existing != null) {
            // Preserve lastTriggered and triggerCount from the stored context
            toStore = new SituationContext(
                context.situationId(), context.correlationKey(), context.tenancyId(),
                context.firstSignal(), context.lastSignal(), context.detections(),
                context.storeVersion(), existing.lastTriggered(), existing.triggerCount());
        }

        store.put(key, toStore);
        return Uni.createFrom().item(toStore.withStoreVersion(newVersion));
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
    public Uni<Integer> removeExpired(Instant cutoff) {
        int[] count = {0};
        store.entrySet().removeIf(entry -> {
            if (!entry.getValue().lastSignal().isAfter(cutoff)) {
                final var key = entry.getKey();
                versions.remove(key);
                claims.remove(key);
                count[0]++;
                return true;
            }
            return false;
        });
        return Uni.createFrom().item(count[0]);
    }

    @Override
    public Uni<Boolean> tryClaimTrigger(String situationId, String correlationKey,
                                         String tenancyId, Instant triggerTime) {
        final var key = new SituationKey(situationId, correlationKey, tenancyId);

        // Attempt to claim
        if (claims.putIfAbsent(key, Boolean.TRUE) != null) {
            return Uni.createFrom().item(false);
        }

        // Claim succeeded — stamp trigger metadata on the stored context
        store.computeIfPresent(key, (k, ctx) ->
            new SituationContext(
                ctx.situationId(), ctx.correlationKey(), ctx.tenancyId(),
                ctx.firstSignal(), ctx.lastSignal(), ctx.detections(),
                ctx.storeVersion(), triggerTime, ctx.triggerCount() + 1)
        );

        return Uni.createFrom().item(true);
    }

    @Override
    public Uni<Void> resetTriggerClaim(String situationId, String correlationKey, String tenancyId) {
        claims.remove(new SituationKey(situationId, correlationKey, tenancyId));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Integer> removeTriggeredBefore(Instant cutoff) {
        int[] count = {0};
        store.entrySet().removeIf(entry -> {
            final var key = entry.getKey();
            final var ctx = entry.getValue();

            if (claims.containsKey(key) && ctx.lastTriggered() != null
                    && !ctx.lastTriggered().isAfter(cutoff)) {
                versions.remove(key);
                claims.remove(key);
                count[0]++;
                return true;
            }
            return false;
        });
        return Uni.createFrom().item(count[0]);
    }

    @Override
    public Uni<List<SituationContext>> findActive(String tenancyId) {
        List<SituationContext> active = new ArrayList<>();

        store.forEach((key, ctx) -> {
            // Include if matches tenancy AND not claimed
            if (key.tenancyId().equals(tenancyId) && !claims.containsKey(key)) {
                long version = versions.get(key).get();
                var withVersion = new SituationContext(
                    ctx.situationId(), ctx.correlationKey(), ctx.tenancyId(),
                    ctx.firstSignal(), ctx.lastSignal(), ctx.detections(),
                    OptionalLong.of(version), ctx.lastTriggered(), ctx.triggerCount());
                active.add(withVersion);
            }
        });

        return Uni.createFrom().item(active);
    }

    @Override
    public Uni<Void> removeAllForSituation(String situationId) {
        store.keySet().removeIf(key -> key.situationId().equals(situationId));
        versions.keySet().removeIf(key -> key.situationId().equals(situationId));
        claims.keySet().removeIf(key -> key.situationId().equals(situationId));
        return Uni.createFrom().voidItem();
    }
}
