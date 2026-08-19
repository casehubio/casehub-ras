package io.casehub.ras.runtime;

import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public record ReplayResult(
        List<SituationChangeEvent> timeline,
        List<TriggerRecord> triggers,
        Map<SituationInstanceKey, SituationContext> finalState,
        List<SkippedEvent> skippedEvents,
        ReplaySummary summary
) {

    public record SituationInstanceKey(String situationId, String correlationKey, String tenancyId) {}

    public record TriggerRecord(UUID caseId, CaseTriggerConfig config, SituationContext context, Instant triggerTime) {}

    public record SkippedEvent(CloudEvent event, String reason) {}

    public record ReplaySummary(
            int eventsProcessed,
            int eventsSkipped,
            int totalTriggers,
            Map<String, Integer> triggersBySituation,
            Map<String, Integer> triggersByTenancy
    ) {}

    public List<SituationChangeEvent> triggersFor(String situationId) {
        return timeline.stream()
                .filter(e -> e.situationId().equals(situationId)
                             && e.changeType() == SituationChangeEvent.ChangeType.TRIGGERED)
                .toList();
    }

    public Optional<SituationContext> stateFor(String situationId, String correlationKey, String tenancyId) {
        return Optional.ofNullable(finalState.get(new SituationInstanceKey(situationId, correlationKey, tenancyId)));
    }

    public boolean didTrigger(String situationId) {
        return triggers.stream().anyMatch(t -> t.context().situationId().equals(situationId));
    }
}
