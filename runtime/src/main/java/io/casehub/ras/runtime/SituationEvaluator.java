package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SituationEvaluator {

    private static final Logger LOG = Logger.getLogger(SituationEvaluator.class.getName());

    private record SituationInstanceKey(String situationId, String correlationKey, String tenancyId) {}

    private final SituationStore store;
    private final RasTriggerPolicy triggerPolicy;
    private final CaseTrigger caseTrigger;
    private final SituationDefinitionRegistry registry;
    private final int maxConflictRetries;
    private final Event<SituationChangeEvent> changeEvent;
    private final ConcurrentHashMap<SituationInstanceKey, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SituationInstanceKey, EventReorderBuffer> buffers = new ConcurrentHashMap<>();

    @Inject
    public SituationEvaluator(SituationStore store, RasTriggerPolicy triggerPolicy,
                              CaseTrigger caseTrigger, SituationDefinitionRegistry registry,
                              @ConfigProperty(name = "ras.evaluator.max-conflict-retries",
                                              defaultValue = "3")
                              int maxConflictRetries,
                              Event<SituationChangeEvent> changeEvent) {
        this.store = store;
        this.triggerPolicy = triggerPolicy;
        this.caseTrigger = caseTrigger;
        this.registry = registry;
        this.maxConflictRetries = maxConflictRetries;
        this.changeEvent = changeEvent;
    }

    public void evaluate(CloudEvent event, SituationDefinition definition,
                         String correlationKey, String tenancyId) {
        String situationId = definition.situationId();
        var key = new SituationInstanceKey(situationId, correlationKey, tenancyId);
        Object lock = locks.computeIfAbsent(key, k -> new Object());

        synchronized (lock) {
            boolean terminated;
            if (definition.eventBufferDelay() != null && event.getTime() != null) {
                var buffer = buffers.computeIfAbsent(key,
                        k -> new EventReorderBuffer(definition.eventBufferDelay(), definition));
                List<CloudEvent> toProcess = buffer.submit(event, Instant.now());
                terminated = false;
                for (CloudEvent e : toProcess) {
                    terminated = processEvent(e, definition, correlationKey, tenancyId);
                    if (terminated) break;
                }
            } else {
                terminated = processEvent(event, definition, correlationKey, tenancyId);
            }
            if (terminated) {
                buffers.remove(key);
                locks.remove(key);
            }
        }
    }

    private boolean processEvent(CloudEvent event, SituationDefinition definition,
                                  String correlationKey, String tenancyId) {
        String situationId = definition.situationId();
        Instant eventTime = extractEventTime(event);

        // Phase 1: Detect (once, never retried)
        SituationContext initialContext = loadContext(situationId, correlationKey,
                                                      tenancyId, definition, eventTime);
        List<DetectionResult> detectionResults = runDetection(event, definition, initialContext);

        // Phase 2: Apply + persist (retried on conflict)
        for (int attempt = 0; attempt <= maxConflictRetries; attempt++) {
            SituationContext context;
            if (attempt == 0) {
                context = initialContext;
            } else {
                LOG.info("Retry " + attempt + "/" + maxConflictRetries
                         + " for situation '" + situationId + "'");
                context = loadContext(situationId, correlationKey,
                                     tenancyId, definition, eventTime);
            }

            for (DetectionResult result : detectionResults) {
                context = context.withDetection(result, eventTime);
            }

            TriggerDecision decision = triggerPolicy.evaluate(context, definition)
                    .await().indefinitely();

            try {
                return executeDecision(decision, context, definition,
                                       situationId, correlationKey, tenancyId, eventTime);
            } catch (SituationConflictException e) {
                if (attempt == maxConflictRetries) {
                    LOG.severe("All retries exhausted for situation '" + situationId
                               + "' correlationKey='" + correlationKey
                               + "', event lost: " + event.getType());
                    return false;
                }
            }
        }
        return false;
    }

    private SituationContext loadContext(String situationId, String correlationKey,
                                         String tenancyId, SituationDefinition definition,
                                         Instant eventTime) {
        SituationContext context = store.find(situationId, correlationKey, tenancyId)
                .await().indefinitely()
                .orElseGet(() -> SituationContext.initial(situationId, correlationKey,
                                                          tenancyId, eventTime));
        if (isExpired(context, definition, eventTime)) {
            closeGanglia(definition, situationId, correlationKey, tenancyId);
            store.remove(situationId, correlationKey, tenancyId).await().indefinitely();
            context = SituationContext.initial(situationId, correlationKey, tenancyId, eventTime);
        }
        return context;
    }

    private List<DetectionResult> runDetection(CloudEvent event,
                                                SituationDefinition definition,
                                                SituationContext context) {
        Set<String> gangliaForEvent = gangliaHandlingEventType(definition, event.getType());
        List<DetectionResult> results = new ArrayList<>();
        for (String ganglionId : gangliaForEvent) {
            Ganglion ganglion = registry.ganglion(ganglionId);
            DetectionResult result = ganglion.detect(event, context).await().indefinitely();
            results.add(result);
        }
        return results;
    }

    private boolean executeDecision(TriggerDecision decision, SituationContext context,
                                     SituationDefinition definition,
                                     String situationId, String correlationKey,
                                     String tenancyId, Instant triggerTime) {
        switch (decision) {
            case TRIGGER -> {
                if (context.storeVersion().isPresent()) {
                    boolean claimed = store.tryClaimTrigger(situationId, correlationKey,
                                                           tenancyId, triggerTime)
                            .await().indefinitely();
                    if (!claimed) {
                        return true;
                    }
                    try {
                        context = store.save(context).await().indefinitely();
                    } catch (SituationConflictException e) {
                        store.resetTriggerClaim(situationId, correlationKey, tenancyId)
                                .await().indefinitely();
                        throw e;
                    }
                } else {
                    context = store.save(context).await().indefinitely();
                    boolean claimed = store.tryClaimTrigger(situationId, correlationKey,
                                                           tenancyId, triggerTime)
                            .await().indefinitely();
                    if (!claimed) {
                        return true;
                    }
                }

                if (definition.triggerAction() instanceof TriggerAction.CreateCase createCase) {
                    try {
                        caseTrigger.fire(createCase.config(), context).await().indefinitely();
                    } catch (RuntimeException ex) {
                        LOG.severe("CaseTrigger.fire() failed for situation '" + situationId
                                   + "': " + ex.getMessage());
                        store.resetTriggerClaim(situationId, correlationKey, tenancyId)
                                .await().indefinitely();
                        return false;
                    }
                    changeEvent.fireAsync(new SituationChangeEvent(
                            tenancyId, situationId, correlationKey,
                            SituationChangeEvent.ChangeType.TRIGGERED, context));
                } else {
                    try {
                        changeEvent.fireAsync(new SituationChangeEvent(
                                tenancyId, situationId, correlationKey,
                                SituationChangeEvent.ChangeType.TRIGGERED, context))
                                .toCompletableFuture().join();
                    } catch (Exception ex) {
                        LOG.severe("SituationChangeEvent delivery failed for situation '"
                                   + situationId + "': " + ex.getMessage());
                        store.resetTriggerClaim(situationId, correlationKey, tenancyId)
                                .await().indefinitely();
                        return false;
                    }
                }

                closeGanglia(definition, situationId, correlationKey, tenancyId);
                return true;
            }
            case TRIGGER_AND_CONTINUE -> {
                // Save-first flow — always persist detection before claiming
                SituationContext savedContext = store.save(context).await().indefinitely();
                boolean claimed = store.tryClaimTrigger(situationId, correlationKey,
                                                       tenancyId, triggerTime)
                        .await().indefinitely();
                if (!claimed) {
                    // Loser continues accumulating — NOT terminated
                    return false;
                }

                if (definition.triggerAction() instanceof TriggerAction.CreateCase createCase) {
                    try {
                        caseTrigger.fire(createCase.config(), savedContext).await().indefinitely();
                    } catch (RuntimeException ex) {
                        LOG.severe("CaseTrigger.fire() failed for situation '" + situationId
                                   + "': " + ex.getMessage());
                        store.resetTriggerClaim(situationId, correlationKey, tenancyId)
                                .await().indefinitely();
                        return false;
                    }
                    changeEvent.fireAsync(new SituationChangeEvent(
                            tenancyId, situationId, correlationKey,
                            SituationChangeEvent.ChangeType.TRIGGERED, savedContext));
                } else {
                    try {
                        changeEvent.fireAsync(new SituationChangeEvent(
                                tenancyId, situationId, correlationKey,
                                SituationChangeEvent.ChangeType.TRIGGERED, savedContext))
                                .toCompletableFuture().join();
                    } catch (Exception ex) {
                        LOG.severe("SituationChangeEvent delivery failed for situation '"
                                   + situationId + "': " + ex.getMessage());
                        store.resetTriggerClaim(situationId, correlationKey, tenancyId)
                                .await().indefinitely();
                        return false;
                    }
                }

                // Reset claim for next trigger cycle
                store.resetTriggerClaim(situationId, correlationKey, tenancyId)
                        .await().indefinitely();
                // Compact and save for continued accumulation
                SituationContext postFireContext = savedContext;
                if (definition.correlationWindow() == null) {
                    postFireContext = compactGanglia(definition, savedContext);
                }
                store.save(postFireContext).await().indefinitely();
                return false;
            }
            case CONTINUE_ACCUMULATING -> {
                if (definition.correlationWindow() == null) {
                    context = compactGanglia(definition, context);
                }
                store.save(context).await().indefinitely();
                return false;
            }
            case DISCARD -> {
                closeGanglia(definition, situationId, correlationKey, tenancyId);
                store.remove(situationId, correlationKey, tenancyId).await().indefinitely();
                changeEvent.fireAsync(new SituationChangeEvent(
                        tenancyId, situationId, correlationKey,
                        SituationChangeEvent.ChangeType.DISCARDED, context));
                return true;
            }
            case RESOLVE -> {
                closeGanglia(definition, situationId, correlationKey, tenancyId);
                store.remove(situationId, correlationKey, tenancyId).await().indefinitely();
                changeEvent.fireAsync(new SituationChangeEvent(
                        tenancyId, situationId, correlationKey,
                        SituationChangeEvent.ChangeType.RESOLVED, context));
                return true;
            }
        }
        return false;
    }

    private Instant extractEventTime(CloudEvent event) {
        OffsetDateTime time = event.getTime();
        return time != null ? time.toInstant() : Instant.now();
    }

    private boolean isExpired(SituationContext context, SituationDefinition definition,
                              Instant eventTime) {
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

    private SituationContext compactGanglia(SituationDefinition definition,
                                            SituationContext context) {
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

    void flushIdleBuffers(Instant now) {
        for (var entry : buffers.entrySet()) {
            var key = entry.getKey();
            var buffer = entry.getValue();
            Object lock = locks.computeIfAbsent(key, k -> new Object());
            synchronized (lock) {
                if (buffer.isIdle(now)) {
                    List<CloudEvent> events = buffer.drainAll();
                    boolean terminated = false;
                    for (CloudEvent e : events) {
                        terminated = processEvent(e, buffer.definition(),
                                     key.correlationKey(), key.tenancyId());
                        if (terminated) break;
                    }
                    if (terminated) {
                        buffers.remove(key);
                        locks.remove(key);
                    }
                }
            }
        }
    }
}
