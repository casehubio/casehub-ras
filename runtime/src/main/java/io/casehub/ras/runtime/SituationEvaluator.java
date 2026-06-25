package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class SituationEvaluator {

    private static final Logger LOG = Logger.getLogger(SituationEvaluator.class.getName());

    private record SituationInstanceKey(String situationId, String correlationKey, String tenancyId) {}

    private final SituationStore store;
    private final RasTriggerPolicy triggerPolicy;
    private final CaseTrigger caseTrigger;
    private final SituationDefinitionRegistry registry;
    private final ConcurrentHashMap<SituationInstanceKey, Object> locks = new ConcurrentHashMap<>();

    @Inject
    public SituationEvaluator(SituationStore store, RasTriggerPolicy triggerPolicy,
                              CaseTrigger caseTrigger, SituationDefinitionRegistry registry) {
        this.store = store;
        this.triggerPolicy = triggerPolicy;
        this.caseTrigger = caseTrigger;
        this.registry = registry;
    }

    public void evaluate(CloudEvent event, SituationDefinition definition,
                         String correlationKey, String tenancyId) {
        String situationId = definition.situationId();
        var key = new SituationInstanceKey(situationId, correlationKey, tenancyId);
        Object lock = locks.computeIfAbsent(key, k -> new Object());

        synchronized (lock) {
            Instant eventTime = extractEventTime(event);

            SituationContext context = store.find(situationId, correlationKey, tenancyId)
                    .await().indefinitely()
                    .orElseGet(() -> SituationContext.initial(situationId, correlationKey,
                                                             tenancyId, eventTime));

            if (isExpired(context, definition, eventTime)) {
                closeGanglia(definition, situationId, correlationKey, tenancyId);
                store.remove(situationId, correlationKey, tenancyId).await().indefinitely();
                context = SituationContext.initial(situationId, correlationKey, tenancyId, eventTime);
            }

            Set<String> gangliaForEvent = gangliaHandlingEventType(definition, event.getType());
            for (String ganglionId : gangliaForEvent) {
                Ganglion ganglion = registry.ganglion(ganglionId);
                DetectionResult result = ganglion.detect(event, context).await().indefinitely();
                context = context.withDetection(result, eventTime);
            }

            TriggerDecision decision = triggerPolicy.evaluate(context, definition)
                    .await().indefinitely();

            switch (decision) {
                case CREATE_CASE -> {
                    try {
                        caseTrigger.fire(definition.triggerConfig(), context).await().indefinitely();
                    } catch (RuntimeException ex) {
                        LOG.severe("CaseTrigger.fire() failed for situation '" + situationId
                                   + "': " + ex.getMessage());
                        store.save(context).await().indefinitely();
                        return;
                    }
                    closeGanglia(definition, situationId, correlationKey, tenancyId);
                    store.remove(situationId, correlationKey, tenancyId).await().indefinitely();
                    locks.remove(key);
                }
                case CONTINUE_ACCUMULATING -> {
                    if (definition.correlationWindow() == null) {
                        context = compactGanglia(definition, context);
                    }
                    store.save(context).await().indefinitely();
                }
                case DISCARD -> {
                    closeGanglia(definition, situationId, correlationKey, tenancyId);
                    store.remove(situationId, correlationKey, tenancyId).await().indefinitely();
                    locks.remove(key);
                }
            }
        }
    }

    private Instant extractEventTime(CloudEvent event) {
        OffsetDateTime time = event.getTime();
        return time != null ? time.toInstant() : Instant.now();
    }

    private boolean isExpired(SituationContext context, SituationDefinition definition, Instant eventTime) {
        if (definition.correlationWindow() == null) return false;
        Instant cutoff = eventTime.minus(definition.correlationWindow());
        return context.lastSignal().isBefore(cutoff);
    }

    private Set<String> gangliaHandlingEventType(SituationDefinition definition, String eventType) {
        Set<String> all = definition.chainMode().referencedGanglia();
        return all.stream()
                .filter(id -> registry.ganglion(id).handledEventTypes().contains(eventType))
                .collect(Collectors.toSet());
    }

    private SituationContext compactGanglia(SituationDefinition definition, SituationContext context) {
        for (String ganglionId : definition.chainMode().referencedGanglia()) {
            try {
                context = registry.ganglion(ganglionId).compact(context).await().indefinitely();
            } catch (RuntimeException ex) {
                LOG.warning("Ganglion '" + ganglionId + "' compact() failed: " + ex.getMessage());
            }
        }
        return context;
    }

    private void closeGanglia(SituationDefinition definition,
                              String situationId, String correlationKey, String tenancyId) {
        for (String ganglionId : definition.chainMode().referencedGanglia()) {
            try {
                registry.ganglion(ganglionId).close(situationId, correlationKey, tenancyId)
                        .await().indefinitely();
            } catch (RuntimeException ex) {
                LOG.warning("Ganglion '" + ganglionId + "' close() failed: " + ex.getMessage());
            }
        }
    }
}
