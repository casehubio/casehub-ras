package io.casehub.ras.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationStore;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.time.Instant;
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

    @Override
    @Transactional(TxType.SUPPORTS)
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
    public Uni<Void> save(SituationContext context) {
        SituationEntity existing = em.createQuery(
                        "SELECT s FROM SituationEntity s WHERE s.situationId = :sid " +
                        "AND s.correlationKey = :ck AND s.tenancyId = :tid",
                        SituationEntity.class)
                .setParameter("sid", context.situationId())
                .setParameter("ck", context.correlationKey())
                .setParameter("tid", context.tenancyId())
                .getResultStream().findFirst().orElse(null);
        if (existing != null) {
            mapper.updateEntity(existing, context);
        } else {
            em.persist(mapper.toEntity(context));
        }
        return Uni.createFrom().voidItem();
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
    public Uni<Void> removeExpired(Instant cutoff) {
        em.createQuery("DELETE FROM SituationEntity s WHERE s.lastSignal <= :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
        return Uni.createFrom().voidItem();
    }
}
