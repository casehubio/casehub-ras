package io.casehub.ras.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.SituationConflictException;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationStore;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class JpaSituationStore implements SituationStore {

    private final EntityManager em;
    private final SituationMapper mapper;

    @Inject
    public JpaSituationStore(EntityManager em, ObjectMapper objectMapper) {
        this.em = em;
        this.mapper = new SituationMapper(objectMapper);
    }

    // REQUIRED (not SUPPORTS) — each find needs its own persistence context
    // so retry loops in SituationEvaluator always see the latest committed state.
    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Optional<SituationContext>> find(String situationId, String correlationKey,
                                                 String tenancyId) {
        SituationEntity entity = em.createQuery(
                        "SELECT s FROM SituationEntity s WHERE s.situationId = :sid " +
                        "AND s.correlationKey = :ck AND s.tenancyId = :tid",
                        SituationEntity.class)
                .setParameter("sid", situationId)
                .setParameter("ck", correlationKey)
                .setParameter("tid", tenancyId)
                .getResultStream().findFirst().orElse(null);
        if (entity == null) {
            return Uni.createFrom().item(Optional.empty());
        }
        return Uni.createFrom().item(Optional.of(mapper.toContext(entity)));
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<SituationContext> save(SituationContext context) {
        SituationEntity existing = em.createQuery(
                        "SELECT s FROM SituationEntity s WHERE s.situationId = :sid " +
                        "AND s.correlationKey = :ck AND s.tenancyId = :tid",
                        SituationEntity.class)
                .setParameter("sid", context.situationId())
                .setParameter("ck", context.correlationKey())
                .setParameter("tid", context.tenancyId())
                .getResultStream().findFirst().orElse(null);

        // Layer 1: application-level storeVersion comparison
        if (existing != null && context.storeVersion().isEmpty()) {
            throw new SituationConflictException(
                    "Entity exists but context has no storeVersion — concurrent insert",
                    null);
        }
        if (existing == null && context.storeVersion().isPresent()) {
            throw new SituationConflictException(
                    "Entity removed but context has storeVersion — concurrent delete",
                    null);
        }
        if (existing != null && context.storeVersion().isPresent()
                && existing.getVersion() != context.storeVersion().getAsLong()) {
            throw new SituationConflictException(
                    "storeVersion mismatch: context=" + context.storeVersion().getAsLong()
                    + " entity=" + existing.getVersion(),
                    null);
        }

        try {
            if (existing != null) {
                mapper.updateEntity(existing, context);
                em.flush();
                return Uni.createFrom().item(context.withStoreVersion(existing.getVersion()));
            } else {
                SituationEntity newEntity = mapper.toEntity(context);
                em.persist(newEntity);
                em.flush();
                return Uni.createFrom().item(context.withStoreVersion(newEntity.getVersion()));
            }
        } catch (jakarta.persistence.OptimisticLockException e) {
            throw new SituationConflictException("Concurrent modification detected", e);
        } catch (jakarta.persistence.PersistenceException e) {
            if (isConstraintViolation(e)) {
                throw new SituationConflictException("Concurrent insert detected", e);
            }
            throw e;
        }
    }

    private boolean isConstraintViolation(Throwable t) {
        while (t != null) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Void> remove(String situationId, String correlationKey, String tenancyId) {
        em.createQuery("DELETE FROM SituationEntity s WHERE s.situationId = :sid " +
                       "AND s.correlationKey = :ck AND s.tenancyId = :tid")
                .setParameter("sid", situationId)
                .setParameter("ck", correlationKey)
                .setParameter("tid", tenancyId)
                .executeUpdate();
        return Uni.createFrom().voidItem();
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Boolean> tryClaimTrigger(String situationId, String correlationKey,
                                         String tenancyId, Instant triggerTime) {
        int updated = em.createQuery(
                        "UPDATE SituationEntity s SET s.policyTriggered = true, " +
                        "s.lastTriggered = :triggerTime, s.triggerCount = s.triggerCount + 1 " +
                        "WHERE s.situationId = :sid AND s.correlationKey = :ck " +
                        "AND s.tenancyId = :tid AND s.policyTriggered = false")
                .setParameter("triggerTime", triggerTime)
                .setParameter("sid", situationId)
                .setParameter("ck", correlationKey)
                .setParameter("tid", tenancyId)
                .executeUpdate();
        return Uni.createFrom().item(updated > 0);
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Void> resetTriggerClaim(String situationId, String correlationKey,
                                        String tenancyId) {
        em.createQuery(
                        "UPDATE SituationEntity s SET s.policyTriggered = false " +
                        "WHERE s.situationId = :sid AND s.correlationKey = :ck " +
                        "AND s.tenancyId = :tid")
                .setParameter("sid", situationId)
                .setParameter("ck", correlationKey)
                .setParameter("tid", tenancyId)
                .executeUpdate();
        return Uni.createFrom().voidItem();
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Integer> removeExpired(Instant cutoff) {
        int removed = em.createQuery("DELETE FROM SituationEntity s WHERE s.lastSignal <= :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
        return Uni.createFrom().item(removed);
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Integer> removeTriggeredBefore(Instant triggerCutoff) {
        int removed = em.createQuery("DELETE FROM SituationEntity s WHERE s.policyTriggered = true " +
                                     "AND s.lastTriggered <= :cutoff")
                        .setParameter("cutoff", triggerCutoff)
                        .executeUpdate();
        return Uni.createFrom().item(removed);
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<List<SituationContext>> findActive(String tenancyId) {
        List<SituationEntity> entities = em.createQuery(
                        "SELECT s FROM SituationEntity s WHERE s.tenancyId = :tid " +
                        "AND s.policyTriggered = false", SituationEntity.class)
                .setParameter("tid", tenancyId)
                .getResultList();
        List<SituationContext> contexts = entities.stream()
                .map(mapper::toContext)
                .toList();
        return Uni.createFrom().item(contexts);
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Void> removeAllForSituation(String situationId) {
        em.createQuery("DELETE FROM SituationEntity e WHERE e.situationId = :situationId")
                .setParameter("situationId", situationId)
                .executeUpdate();
        return Uni.createFrom().voidItem();
    }
}
