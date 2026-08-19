package io.casehub.ras.runtime;

import io.casehub.ras.api.CaseTrigger;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.DefaultCorrelationKeyExtractor;
import io.casehub.ras.api.Ganglion;
import io.casehub.ras.api.OutcomeLedger;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationDefinitionProvider;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.SituationStore;
import io.casehub.ras.api.SuppressionStrategy;
import io.cloudevents.CloudEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

public class SituationReplayRunner {

    private final SituationDefinitionRegistry registry;
    private final SituationEvaluator evaluator;
    private final List<CloudEvent> events;
    private final ReplayErrorHandling errorHandling;
    private final CollectingChangeEvent collectingChangeEvent;
    private final CollectingCaseTrigger collectingCaseTrigger;
    private final CollectingSituationStore collectingSituationStore;

    private SituationReplayRunner(Builder builder) {
        this.events = List.copyOf(builder.events);
        this.errorHandling = builder.errorHandling;

        List<SituationDefinitionProvider> providers = new ArrayList<>();
        if (builder.providers != null) {
            providers.addAll(builder.providers);
        }
        if (builder.registrations != null) {
            providers.add(() -> builder.registrations);
        }

        this.registry = new SituationDefinitionRegistry(
                providers, builder.ganglia != null ? builder.ganglia : List.of());

        this.collectingChangeEvent = new CollectingChangeEvent();
        this.collectingCaseTrigger = new CollectingCaseTrigger(
                builder.caseTrigger != null ? builder.caseTrigger : new NoOpCaseTrigger());

        SituationStore baseStore = builder.store != null ? builder.store : createDefaultStore();
        this.collectingSituationStore = new CollectingSituationStore(baseStore);

        var meterRegistry = new SimpleMeterRegistry();
        var metrics = new RasMetrics(registry);
        metrics.setMeterRegistry(meterRegistry);
        metrics.init();

        this.evaluator = new SituationEvaluator(
                collectingSituationStore, new DefaultRasTriggerPolicy(),
                collectingCaseTrigger, registry, 3,
                collectingChangeEvent, metrics,
                builder.suppressionStrategy, builder.outcomeLedger, builder.feedbackState);
    }

    public static Builder builder() {
        return new Builder();
    }

    public ReplayResult run() {
        int eventsProcessed = 0;
        List<ReplayResult.SkippedEvent> skippedEvents = new ArrayList<>();

        for (CloudEvent event : events) {
            String tenancyId = extractTenancyId(event);
            if (tenancyId == null) {
                if (errorHandling == ReplayErrorHandling.STRICT) {
                    throw new IllegalArgumentException(
                            "CloudEvent without tenancyid extension: " + event.getId());
                }
                skippedEvents.add(new ReplayResult.SkippedEvent(event, "missing tenancyid extension"));
                continue;
            }

            List<SituationRegistration> registrations = registry.findByEventType(event.getType());
            if (registrations.isEmpty()) {
                eventsProcessed++;
                continue;
            }

            for (SituationRegistration reg : registrations) {
                if (reg.eventFilter() != null) {
                    try {
                        if (!reg.eventFilter().accepts(event)) {
                            continue;
                        }
                    } catch (RuntimeException ex) {
                        if (errorHandling == ReplayErrorHandling.STRICT) {
                            throw ex;
                        }
                        skippedEvents.add(new ReplayResult.SkippedEvent(event,
                                "event filter error: " + ex.getMessage()));
                        continue;
                    }
                }

                String correlationKey;
                try {
                    correlationKey = reg.correlationKeyExtractor().extract(event);
                } catch (RuntimeException ex) {
                    if (errorHandling == ReplayErrorHandling.STRICT) {
                        throw ex;
                    }
                    correlationKey = DefaultCorrelationKeyExtractor.INSTANCE.extract(event);
                }

                evaluator.evaluate(event, reg.definition(), correlationKey, tenancyId);
            }
            eventsProcessed++;
        }

        evaluator.drainAllBuffers();

        return buildResult(eventsProcessed, skippedEvents);
    }

    private ReplayResult buildResult(int eventsProcessed, List<ReplayResult.SkippedEvent> skippedEvents) {
        List<SituationChangeEvent> timeline = collectingChangeEvent.firedEvents();
        List<ReplayResult.TriggerRecord> triggers = collectingCaseTrigger.triggerRecords();
        Map<ReplayResult.SituationInstanceKey, SituationContext> finalState =
                collectingSituationStore.allLatestState();

        Map<String, Integer> triggersBySituation = new HashMap<>();
        Map<String, Integer> triggersByTenancy = new HashMap<>();
        for (var trigger : triggers) {
            triggersBySituation.merge(trigger.context().situationId(), 1, Integer::sum);
            triggersByTenancy.merge(trigger.context().tenancyId(), 1, Integer::sum);
        }

        var summary = new ReplayResult.ReplaySummary(
                eventsProcessed, skippedEvents.size(), triggers.size(),
                Map.copyOf(triggersBySituation), Map.copyOf(triggersByTenancy));

        return new ReplayResult(
                List.copyOf(timeline), List.copyOf(triggers),
                Map.copyOf(finalState), List.copyOf(skippedEvents), summary);
    }

    private static String extractTenancyId(CloudEvent event) {
        Object ext = event.getExtension("tenancyid");
        return ext != null ? ext.toString() : null;
    }

    private static SituationStore createDefaultStore() {
        try {
            return (SituationStore) Class.forName(
                    "io.casehub.ras.persistence.memory.InMemorySituationStore")
                    .getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "No SituationStore provided and InMemorySituationStore not on classpath. "
                    + "Add casehub-ras-persistence-memory or provide a store via .withStore()", e);
        }
    }

    // --- Collecting decorators ---

    static class CollectingChangeEvent implements Event<SituationChangeEvent> {
        private final CopyOnWriteArrayList<SituationChangeEvent> fired = new CopyOnWriteArrayList<>();

        List<SituationChangeEvent> firedEvents() { return List.copyOf(fired); }

        @Override public void fire(SituationChangeEvent event) { fired.add(event); }

        @Override
        public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event) {
            fired.add(event);
            return CompletableFuture.completedFuture(event);
        }

        @Override
        public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
            fired.add(event);
            return CompletableFuture.completedFuture(event);
        }

        @Override public Event<SituationChangeEvent> select(Annotation... qualifiers) { return this; }

        @Override
        public <U extends SituationChangeEvent> Event<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends SituationChangeEvent> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }
    }

    static class CollectingCaseTrigger implements CaseTrigger {
        private final CaseTrigger delegate;
        private final List<ReplayResult.TriggerRecord> records = new CopyOnWriteArrayList<>();

        CollectingCaseTrigger(CaseTrigger delegate) { this.delegate = delegate; }

        @Override
        public UUID fire(CaseTriggerConfig config, SituationContext context) {
            UUID caseId = delegate.fire(config, context);
            records.add(new ReplayResult.TriggerRecord(caseId, config, context, Instant.now()));
            return caseId;
        }

        List<ReplayResult.TriggerRecord> triggerRecords() { return List.copyOf(records); }
    }

    static class CollectingSituationStore implements SituationStore {
        private final SituationStore delegate;
        private final Map<ReplayResult.SituationInstanceKey, SituationContext> latestState = new HashMap<>();

        CollectingSituationStore(SituationStore delegate) { this.delegate = delegate; }

        @Override
        public java.util.Optional<SituationContext> find(String situationId, String correlationKey, String tenancyId) {
            return delegate.find(situationId, correlationKey, tenancyId);
        }

        @Override
        public SituationContext save(SituationContext context) {
            SituationContext saved = delegate.save(context);
            latestState.put(new ReplayResult.SituationInstanceKey(
                    saved.situationId(), saved.correlationKey(), saved.tenancyId()), saved);
            return saved;
        }

        @Override
        public void remove(String situationId, String correlationKey, String tenancyId) {
            delegate.remove(situationId, correlationKey, tenancyId);
            latestState.remove(new ReplayResult.SituationInstanceKey(situationId, correlationKey, tenancyId));
        }

        @Override
        public int removeExpired(Instant cutoff) { return delegate.removeExpired(cutoff); }

        @Override
        public void removeAllForSituation(String situationId) { delegate.removeAllForSituation(situationId); }

        @Override
        public boolean tryClaimTrigger(String situationId, String correlationKey, String tenancyId, Instant triggerTime) {
            return delegate.tryClaimTrigger(situationId, correlationKey, tenancyId, triggerTime);
        }

        @Override
        public void resetTriggerClaim(String situationId, String correlationKey, String tenancyId) {
            delegate.resetTriggerClaim(situationId, correlationKey, tenancyId);
        }

        @Override
        public int removeTriggeredBefore(Instant cutoff) { return delegate.removeTriggeredBefore(cutoff); }

        @Override
        public List<SituationContext> findActive(String tenancyId) { return delegate.findActive(tenancyId); }

        Map<ReplayResult.SituationInstanceKey, SituationContext> allLatestState() {
            return Map.copyOf(latestState);
        }
    }

    private static class NoOpCaseTrigger implements CaseTrigger {
        @Override
        public UUID fire(CaseTriggerConfig config, SituationContext context) {
            return UUID.randomUUID();
        }
    }

    // --- Builder ---

    public static class Builder {
        private List<SituationRegistration> registrations;
        private List<SituationDefinitionProvider> providers;
        private List<Ganglion> ganglia;
        private List<CloudEvent> events;
        private SituationStore store;
        private CaseTrigger caseTrigger;
        private SuppressionStrategy suppressionStrategy;
        private OutcomeLedger outcomeLedger;
        private FeedbackState feedbackState;
        private ReplayErrorHandling errorHandling = ReplayErrorHandling.STRICT;

        public Builder withRegistrations(List<SituationRegistration> registrations) {
            this.registrations = List.copyOf(registrations);
            return this;
        }

        public Builder withProvider(SituationDefinitionProvider provider) {
            if (this.providers == null) this.providers = new ArrayList<>();
            this.providers.add(provider);
            return this;
        }

        public Builder withYaml(String classpathResource) {
            return withProvider(new YamlSituationDefinitionProvider(classpathResource));
        }

        public Builder withGanglia(List<Ganglion> ganglia) {
            this.ganglia = List.copyOf(ganglia);
            return this;
        }

        public Builder withEvents(List<CloudEvent> events) {
            this.events = events;
            return this;
        }

        public Builder withStore(SituationStore store) {
            this.store = store;
            return this;
        }

        public Builder withCaseTrigger(CaseTrigger caseTrigger) {
            this.caseTrigger = caseTrigger;
            return this;
        }

        public Builder withSuppressionStrategy(SuppressionStrategy strategy) {
            this.suppressionStrategy = strategy;
            return this;
        }

        public Builder withOutcomeLedger(OutcomeLedger ledger) {
            this.outcomeLedger = ledger;
            return this;
        }

        public Builder withFeedbackState(FeedbackState feedbackState) {
            this.feedbackState = feedbackState;
            return this;
        }

        public Builder withErrorHandling(ReplayErrorHandling errorHandling) {
            this.errorHandling = errorHandling;
            return this;
        }

        public SituationReplayRunner build() {
            if (events == null || events.isEmpty()) {
                throw new IllegalArgumentException("Events list is required and must not be empty");
            }
            if (registrations == null && providers == null) {
                throw new IllegalArgumentException(
                        "At least one definition source required: withRegistrations(), withProvider(), or withYaml()");
            }
            return new SituationReplayRunner(this);
        }
    }
}
