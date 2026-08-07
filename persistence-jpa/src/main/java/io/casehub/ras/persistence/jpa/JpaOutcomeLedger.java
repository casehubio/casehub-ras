package io.casehub.ras.persistence.jpa;

import io.casehub.ras.api.OutcomeClassification;
import io.casehub.ras.api.OutcomeLedger;
import io.casehub.ras.api.OutcomeRecord;
import io.casehub.ras.api.OutcomeStatistics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class JpaOutcomeLedger implements OutcomeLedger {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(JpaOutcomeLedger.class.getName());

    private final EntityManager em;

    @Inject
    public JpaOutcomeLedger(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional
    public void record(OutcomeRecord record) {
        int inserted = em.createNativeQuery(
                "INSERT INTO ras_outcome_record " +
                "(situation_id, correlation_key, tenancy_id, outcome_label, classification, closed_at, case_id) " +
                "VALUES (:sid, :ck, :tid, :label, :cls, :closedAt, :caseId) " +
                "ON CONFLICT (case_id) DO NOTHING")
                .setParameter("sid", record.situationId())
                .setParameter("ck", record.correlationKey())
                .setParameter("tid", record.tenancyId())
                .setParameter("label", record.outcomeLabel())
                .setParameter("cls", record.classification().name())
                .setParameter("closedAt", record.closedAt())
                .setParameter("caseId", record.caseId())
                .executeUpdate();
        if (inserted == 0) {
            LOG.fine("Duplicate outcome record for case_id " + record.caseId() + " — skipped");
        }
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public OutcomeStatistics statistics(String situationId, String tenancyId, Instant since) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT classification, COUNT(*) FROM ras_outcome_record " +
                "WHERE situation_id = :sid AND tenancy_id = :tid AND closed_at >= :since " +
                "GROUP BY classification")
                .setParameter("sid", situationId)
                .setParameter("tid", tenancyId)
                .setParameter("since", since)
                .getResultList();

        long noise = 0, confirmed = 0, neutral = 0;
        for (Object[] row : rows) {
            String cls = (String) row[0];
            long count = ((Number) row[1]).longValue();
            switch (OutcomeClassification.valueOf(cls)) {
                case NOISE -> noise = count;
                case CONFIRMED -> confirmed = count;
                case NEUTRAL -> neutral = count;
            }
        }
        return new OutcomeStatistics(situationId, tenancyId,
                noise + confirmed + neutral, noise, confirmed, neutral, since);
    }

    @Override
    @Transactional
    public Optional<Instant> lastNoiseDismissalTime(String situationId, String correlationKey,
                                                     String tenancyId) {
        List<Instant> results = em.createQuery(
                "SELECT MAX(e.closedAt) FROM OutcomeRecordEntity e " +
                "WHERE e.situationId = :sid AND e.correlationKey = :ck AND e.tenancyId = :tid " +
                "AND e.classification = :cls", Instant.class)
                .setParameter("sid", situationId)
                .setParameter("ck", correlationKey)
                .setParameter("tid", tenancyId)
                .setParameter("cls", OutcomeClassification.NOISE)
                .getResultList();

        if (results.isEmpty() || results.get(0) == null) {
            return Optional.empty();
        }
        return Optional.of(results.get(0));
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Long> countByLabel(String situationId, String tenancyId, Instant since) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT outcome_label, COUNT(*) FROM ras_outcome_record " +
                "WHERE situation_id = :sid AND tenancy_id = :tid AND closed_at >= :since " +
                "GROUP BY outcome_label")
                .setParameter("sid", situationId)
                .setParameter("tid", tenancyId)
                .setParameter("since", since)
                .getResultList();

        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.put((String) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public Set<String> distinctTenancies(String situationId) {
        List<String> tenancies = em.createNativeQuery(
                "SELECT DISTINCT tenancy_id FROM ras_outcome_record " +
                "WHERE situation_id = :sid")
                .setParameter("sid", situationId)
                .getResultList();
        return Set.copyOf(tenancies);
    }

    @Override
    @Transactional
    public int removeRecordsBefore(String situationId, Instant cutoff) {
        return em.createNativeQuery(
                "DELETE FROM ras_outcome_record " +
                "WHERE situation_id = :sid AND closed_at < :cutoff")
                .setParameter("sid", situationId)
                .setParameter("cutoff", cutoff)
                .executeUpdate();
    }
}
