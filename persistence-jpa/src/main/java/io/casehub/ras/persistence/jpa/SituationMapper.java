package io.casehub.ras.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.TimestampedDetection;
import java.util.List;
import java.util.OptionalLong;

class SituationMapper {

    private static final TypeReference<List<TimestampedDetection>> DETECTIONS_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    SituationMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    SituationContext toContext(SituationEntity entity) {
        List<TimestampedDetection> detections = deserializeDetections(entity.getDetections());
        return new SituationContext(
                entity.getSituationId(),
                entity.getCorrelationKey(),
                entity.getTenancyId(),
                entity.getFirstSignal(),
                entity.getLastSignal(),
                detections,
                OptionalLong.of(entity.getVersion()),
                null,
                0);
    }

    SituationEntity toEntity(SituationContext context) {
        return new SituationEntity(
                context.situationId(),
                context.correlationKey(),
                context.tenancyId(),
                context.firstSignal(),
                context.lastSignal(),
                serializeDetections(context.detections()));
    }

    void updateEntity(SituationEntity entity, SituationContext context) {
        entity.setFirstSignal(context.firstSignal());
        entity.setLastSignal(context.lastSignal());
        entity.setDetections(serializeDetections(context.detections()));
    }

    String serializeDetections(List<TimestampedDetection> detections) {
        try {
            return objectMapper.writeValueAsString(detections);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize detections", e);
        }
    }

    List<TimestampedDetection> deserializeDetections(String json) {
        try {
            return objectMapper.readValue(json, DETECTIONS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize detections: " + json, e);
        }
    }
}
