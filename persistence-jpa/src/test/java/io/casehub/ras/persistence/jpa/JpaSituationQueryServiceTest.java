package io.casehub.ras.persistence.jpa;

import io.casehub.ras.api.AbstractSituationQueryServiceContractTest;
import io.casehub.ras.api.SituationEvent;
import io.casehub.ras.api.SituationEventRetention;
import io.casehub.ras.api.SituationQueryService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;

import java.time.Instant;

@QuarkusTest
class JpaSituationQueryServiceTest extends AbstractSituationQueryServiceContractTest {

    @Inject
    JpaSituationQueryService jpaQueryService;

    @Inject
    EntityManager em;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Override
    protected SituationQueryService createQueryService() {
        return jpaQueryService;
    }

    @Override
    protected SituationEventRetention createRetention() {
        return jpaQueryService;
    }

    @BeforeEach
    @Transactional
    void cleanUpData() {
        em.createQuery("DELETE FROM SituationEventEntity").executeUpdate();
    }

    @Override
    @Transactional
    protected void seed(SituationEvent event) {
        String evidence = serializeMap(event.evidence());
        String metadata = serializeMap(event.metadata());
        SituationEventEntity entity = new SituationEventEntity(
                event.situationId(), event.correlationKey(), event.tenancyId(),
                event.changeType().name(), event.eventTime(), event.firstSeen(),
                event.confidence(), event.detectionCount(), event.triggerCount(),
                evidence, metadata);
        em.persist(entity);
    }

    private String serializeMap(java.util.Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T23:59:59Z");
}
