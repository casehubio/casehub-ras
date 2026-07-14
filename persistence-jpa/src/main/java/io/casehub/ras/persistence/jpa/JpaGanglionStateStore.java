package io.casehub.ras.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.*;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.Optional;
import java.util.OptionalLong;

@ApplicationScoped
public class JpaGanglionStateStore implements GanglionStateStore {

    private final EntityManager em;
    private final ObjectMapper objectMapper;

    @Inject
    public JpaGanglionStateStore(EntityManager em, ObjectMapper objectMapper) {
        this.em = em;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Optional<GanglionState>> load(GanglionStateKey key) {
        GanglionStateEntity entity = findEntity(key);
        if (entity == null) {
            return Uni.createFrom().item(Optional.empty());
        }
        double[] values = deserializeState(entity.getState());
        return Uni.createFrom().item(Optional.of(
                new GanglionState(values, OptionalLong.of(entity.getVersion()))));
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Void> save(GanglionStateKey key, GanglionState state) {
        GanglionStateEntity existing = findEntity(key);

        if (existing != null && state.storeVersion().isEmpty()) {
            throw new GanglionStateConflictException(
                    "Entity exists but state has no storeVersion — concurrent insert", null);
        }
        if (existing == null && state.storeVersion().isPresent()) {
            throw new GanglionStateConflictException(
                    "Entity removed but state has storeVersion — concurrent delete", null);
        }
        if (existing != null && state.storeVersion().isPresent()
                && existing.getVersion() != state.storeVersion().getAsLong()) {
            throw new GanglionStateConflictException(
                    "storeVersion mismatch: state=" + state.storeVersion().getAsLong()
                    + " entity=" + existing.getVersion(), null);
        }

        try {
            String serialized = serializeState(state.values());
            if (existing != null) {
                existing.setState(serialized);
                em.flush();
            } else {
                GanglionStateEntity newEntity = new GanglionStateEntity(
                        key.ganglionId(), key.situationId(),
                        key.correlationKey(), key.tenancyId(), serialized);
                em.persist(newEntity);
                em.flush();
            }
        } catch (jakarta.persistence.OptimisticLockException e) {
            throw new GanglionStateConflictException("Concurrent modification detected", e);
        } catch (jakarta.persistence.PersistenceException e) {
            if (isConstraintViolation(e)) {
                throw new GanglionStateConflictException("Concurrent insert detected", e);
            }
            throw e;
        }

        return Uni.createFrom().voidItem();
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Void> remove(GanglionStateKey key) {
        em.createQuery("DELETE FROM GanglionStateEntity e " +
                       "WHERE e.ganglionId = :gid AND e.situationId = :sid " +
                       "AND e.correlationKey = :ck AND e.tenancyId = :tid")
                .setParameter("gid", key.ganglionId())
                .setParameter("sid", key.situationId())
                .setParameter("ck", key.correlationKey())
                .setParameter("tid", key.tenancyId())
                .executeUpdate();
        return Uni.createFrom().voidItem();
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Void> removeForSituation(String situationId) {
        em.createQuery("DELETE FROM GanglionStateEntity e WHERE e.situationId = :sid")
                .setParameter("sid", situationId)
                .executeUpdate();
        return Uni.createFrom().voidItem();
    }

    @Override
    @Transactional(TxType.REQUIRED)
    public Uni<Integer> removeOrphaned() {
        int removed = em.createNativeQuery(
                "DELETE FROM ras_ganglion_state gs " +
                "WHERE NOT EXISTS (" +
                "  SELECT 1 FROM ras_situation s " +
                "  WHERE s.situation_id = gs.situation_id " +
                "  AND s.correlation_key = gs.correlation_key " +
                "  AND s.tenancy_id = gs.tenancy_id)")
                .executeUpdate();
        return Uni.createFrom().item(removed);
    }

    private GanglionStateEntity findEntity(GanglionStateKey key) {
        return em.createQuery(
                "SELECT e FROM GanglionStateEntity e " +
                "WHERE e.ganglionId = :gid AND e.situationId = :sid " +
                "AND e.correlationKey = :ck AND e.tenancyId = :tid",
                GanglionStateEntity.class)
                .setParameter("gid", key.ganglionId())
                .setParameter("sid", key.situationId())
                .setParameter("ck", key.correlationKey())
                .setParameter("tid", key.tenancyId())
                .getResultStream().findFirst().orElse(null);
    }

    private String serializeState(double[] values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ganglion state", e);
        }
    }

    private double[] deserializeState(String json) {
        try {
            return objectMapper.readValue(json, double[].class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize ganglion state: " + json, e);
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
}
