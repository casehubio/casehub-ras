package io.casehub.ras.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.logging.Logger;

@ApplicationScoped
public class SituationEventRecorder {

    private static final Logger LOG = Logger.getLogger(SituationEventRecorder.class.getName());

    private final EntityManager em;
    private final ObjectMapper objectMapper;

    @Inject
    public SituationEventRecorder(EntityManager em, ObjectMapper objectMapper) {
        this.em = em;
        this.objectMapper = objectMapper;
    }

    @Transactional
    void onSituationChange(@ObservesAsync SituationChangeEvent changeEvent) {
        try {
            SituationEvent projected = SituationEvent.from(changeEvent,
                    changeEvent.context().lastSignal());
            SituationEventEntity entity = toEntity(projected);
            em.persist(entity);
        } catch (Exception e) {
            LOG.warning("Failed to record situation event: " + e.getMessage());
        }
    }

    private SituationEventEntity toEntity(SituationEvent event) {
        return new SituationEventEntity(
                event.situationId(), event.correlationKey(), event.tenancyId(),
                event.changeType().name(), event.eventTime(), event.firstSeen(),
                event.confidence(), event.detectionCount(), event.triggerCount(),
                serializeMap(event.evidence()), serializeMap(event.metadata()));
    }

    private String serializeMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            LOG.warning("Failed to serialize map: " + e.getMessage());
            return null;
        }
    }
}
