package io.casehub.ras.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationEvent;
import io.casehub.ras.api.SituationEventRetention;
import io.casehub.ras.api.SituationQueryService;
import io.casehub.ras.api.SituationSummary;
import io.casehub.ras.api.TenantHealth;
import io.casehub.ras.api.TrendResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@ApplicationScoped
public class JpaSituationQueryService implements SituationQueryService, SituationEventRetention {

    private static final Logger LOG = Logger.getLogger(JpaSituationQueryService.class.getName());
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final EntityManager em;
    private final ObjectMapper objectMapper;

    @Inject
    public JpaSituationQueryService(EntityManager em, ObjectMapper objectMapper) {
        this.em = em;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public List<SituationEvent> history(String tenancyId, Instant from, Instant to) {
        return em.createQuery(
                        "SELECT e FROM SituationEventEntity e " +
                        "WHERE e.tenancyId = :tid AND e.eventTime >= :from AND e.eventTime < :to " +
                        "ORDER BY e.eventTime ASC", SituationEventEntity.class)
                .setParameter("tid", tenancyId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList()
                .stream().map(this::toEvent).toList();
    }

    @Override
    @Transactional
    public List<SituationEvent> history(String tenancyId, String situationId,
                                         Instant from, Instant to) {
        return em.createQuery(
                        "SELECT e FROM SituationEventEntity e " +
                        "WHERE e.tenancyId = :tid AND e.situationId = :sid " +
                        "AND e.eventTime >= :from AND e.eventTime < :to " +
                        "ORDER BY e.eventTime ASC", SituationEventEntity.class)
                .setParameter("tid", tenancyId)
                .setParameter("sid", situationId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList()
                .stream().map(this::toEvent).toList();
    }

    @Override
    @Transactional
    public List<SituationEvent> history(String tenancyId, String situationId,
                                         String correlationKey, Instant from, Instant to) {
        return em.createQuery(
                        "SELECT e FROM SituationEventEntity e " +
                        "WHERE e.tenancyId = :tid AND e.situationId = :sid " +
                        "AND e.correlationKey = :ck " +
                        "AND e.eventTime >= :from AND e.eventTime < :to " +
                        "ORDER BY e.eventTime ASC", SituationEventEntity.class)
                .setParameter("tid", tenancyId)
                .setParameter("sid", situationId)
                .setParameter("ck", correlationKey)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList()
                .stream().map(this::toEvent).toList();
    }

    @Override
    @Transactional
    public long triggerCount(String tenancyId, String situationId,
                              Instant from, Instant to) {
        return em.createQuery(
                        "SELECT COUNT(e) FROM SituationEventEntity e " +
                        "WHERE e.tenancyId = :tid AND e.situationId = :sid " +
                        "AND e.changeType = :ct " +
                        "AND e.eventTime >= :from AND e.eventTime < :to", Long.class)
                .setParameter("tid", tenancyId)
                .setParameter("sid", situationId)
                .setParameter("ct", SituationChangeEvent.ChangeType.TRIGGERED.name())
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
    }

    @Override
    @Transactional
    public TrendResult trend(String tenancyId, String situationId,
                              Duration window, Duration baseline, Instant asOf) {
        Instant windowStart   = asOf.minus(window);
        Instant baselineStart = windowStart.minus(baseline);
        long    currentCount  = triggerCount(tenancyId, situationId, windowStart, asOf);
        long    baselineCount = triggerCount(tenancyId, situationId, baselineStart, windowStart);
        return TrendResult.compute(currentCount, baselineCount, window, baseline);}

    @Override
    @Transactional
    public TenantHealth health(String tenancyId, Duration window, Instant asOf) {
        Instant windowStart = asOf.minus(window);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createQuery(
                        "SELECT e.situationId, COUNT(e), " +
                        "SUM(CASE WHEN e.changeType = :ct THEN 1 ELSE 0 END), " +
                        "MAX(e.eventTime) " +
                        "FROM SituationEventEntity e " +
                        "WHERE e.tenancyId = :tid " +
                        "AND e.eventTime >= :from AND e.eventTime < :to " +
                        "GROUP BY e.situationId")
                .setParameter("tid", tenancyId)
                .setParameter("ct", SituationChangeEvent.ChangeType.TRIGGERED.name())
                .setParameter("from", windowStart)
                .setParameter("to", asOf)
                .getResultList();

        long totalEvents = rows.stream().mapToLong(r -> (Long) r[1]).sum();

        List<SituationSummary> summaries = rows.stream()
                .map(r -> new SituationSummary(
                        (String) r[0],
                        (Long) r[1],
                        (Long) r[2],
                        (Instant) r[3]))
                .toList();

        return new TenantHealth(tenancyId, windowStart, asOf, totalEvents, summaries);
    }

    @Override
    @Transactional
    public int removeEventsBefore(Instant cutoff) {
        return em.createQuery("DELETE FROM SituationEventEntity e WHERE e.eventTime < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
    }

    private SituationEvent toEvent(SituationEventEntity entity) {
        return new SituationEvent(
                entity.getSituationId(), entity.getCorrelationKey(), entity.getTenancyId(),
                SituationChangeEvent.ChangeType.valueOf(entity.getChangeType()),
                entity.getEventTime(), entity.getFirstSeen(),
                entity.getConfidence(), entity.getDetectionCount(), entity.getTriggerCount(),
                deserializeMap(entity.getEvidence()), deserializeMap(entity.getMetadata()));
    }

    private Map<String, Object> deserializeMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            LOG.warning("Failed to deserialize map: " + e.getMessage());
            return Map.of();
        }
    }
}
