package io.casehub.ras.api;

import io.cloudevents.CloudEvent;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public abstract class JavaSwitchGanglion implements Ganglion {

    private final String ganglionId;
    private final Set<String> handledEventTypes;

    protected JavaSwitchGanglion(String ganglionId, Set<String> handledEventTypes) {
        this.ganglionId = Objects.requireNonNull(ganglionId, "ganglionId");
        if (handledEventTypes == null || handledEventTypes.isEmpty()) {
            throw new IllegalArgumentException("handledEventTypes must not be empty");
        }
        this.handledEventTypes = Set.copyOf(handledEventTypes);
    }

    @Override
    public final String ganglionId() { return ganglionId; }

    @Override
    public final Set<String> handledEventTypes() { return handledEventTypes; }

    protected abstract DetectionResult evaluate(CloudEvent event, SituationContext context);

    @Override
    public final DetectionResult detect(CloudEvent event, SituationContext context) {
        return evaluate(event, context);
    }

    protected DetectionResult detected(double confidence, Map<String, Object> evidence) {
        return new DetectionResult(ganglionId, confidence, DetectionSignal.DETECTED, evidence);
    }

    protected DetectionResult detected(double confidence) {
        return detected(confidence, Map.of());
    }

    protected DetectionResult weak(double confidence, Map<String, Object> evidence) {
        return new DetectionResult(ganglionId, confidence, DetectionSignal.WEAK, evidence);
    }

    protected DetectionResult weak(double confidence) {
        return weak(confidence, Map.of());
    }

    protected DetectionResult noise() {
        return new DetectionResult(ganglionId, 0.0, DetectionSignal.NOISE, Map.of());
    }

    protected DetectionResult anti(double confidence, Map<String, Object> evidence) {
        return new DetectionResult(ganglionId, confidence, DetectionSignal.ANTI, evidence);
    }

    protected DetectionResult anti(double confidence) {
        return anti(confidence, Map.of());
    }
}
