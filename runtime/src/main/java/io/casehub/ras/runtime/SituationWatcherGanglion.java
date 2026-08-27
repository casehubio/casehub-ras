package io.casehub.ras.runtime;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.Ganglion;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class SituationWatcherGanglion implements Ganglion {

    private static final String EVENT_TYPE_PREFIX = "ras.situation.";

    private final String ganglionId;
    private final Map<SituationChangeEvent.ChangeType, DetectionSignal> changeTypeMapping;
    private final Set<String> handledTypes;

    public SituationWatcherGanglion(String ganglionId,
                                     Map<SituationChangeEvent.ChangeType, DetectionSignal> changeTypeMapping) {
        this.ganglionId = ganglionId;
        this.changeTypeMapping = Map.copyOf(changeTypeMapping);
        this.handledTypes = changeTypeMapping.keySet().stream()
                .map(ct -> EVENT_TYPE_PREFIX + ct.name().toLowerCase())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String ganglionId() { return ganglionId; }

    @Override
    public Set<String> handledEventTypes() { return handledTypes; }

    @Override
    public DetectionResult detect(CloudEvent event, SituationContext context) {
        Object changeTypeExt = event.getExtension("changetype");
        if (changeTypeExt == null) {
            return new DetectionResult(ganglionId, 0.0, DetectionSignal.NOISE, Map.of());
        }

        SituationChangeEvent.ChangeType changeType;
        try {
            changeType = SituationChangeEvent.ChangeType.valueOf(changeTypeExt.toString());
        } catch (IllegalArgumentException e) {
            return new DetectionResult(ganglionId, 0.0, DetectionSignal.NOISE, Map.of());
        }

        DetectionSignal signal = changeTypeMapping.getOrDefault(changeType, DetectionSignal.NOISE);
        Map<String, Object> evidence = Map.of(
                "childSituationId", String.valueOf(event.getExtension("situationid")),
                "childCorrelationKey", String.valueOf(event.getExtension("correlationkey")),
                "childChangeType", changeTypeExt.toString());

        return new DetectionResult(ganglionId, 1.0, signal, evidence);
    }
}
