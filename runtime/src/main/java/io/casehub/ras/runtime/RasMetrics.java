package io.casehub.ras.runtime;

import io.casehub.ras.api.TriggerDecision;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.function.Supplier;

@ApplicationScoped
public class RasMetrics {

    private final SituationDefinitionRegistry registry;

    @Inject
    Instance<MeterRegistry> meterRegistryInstance;

    private MeterRegistry metrics;

    @Inject
    public RasMetrics(SituationDefinitionRegistry registry) {
        this.registry = registry;
    }

    void setMeterRegistry(MeterRegistry registry) {
        this.metrics = registry;
    }

    @PostConstruct
    void init() {
        if (metrics == null && meterRegistryInstance != null && meterRegistryInstance.isResolvable()) {
            metrics = meterRegistryInstance.get();
        }
        if (metrics != null) {
            metrics.gauge("ras.registry.definitions.active", List.of(),
                          registry, r -> r.definitionCount());
        }
    }

    public void eventReceived(String eventType) {
        counter("ras.engine.events.received", "event_type", eventType);
    }

    public void eventSkipped(String reason) {
        counter("ras.engine.events.skipped", "reason", reason);
    }

    public void eventRouted(String situationId, String tenancyId) {
        counter("ras.engine.events.routed",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public void eventFiltered(String situationId, String tenancyId) {
        counter("ras.events.filtered", "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public void expressionError(String situationId, String expressionPoint) {
        counter("ras.expression.error", "situation_id", situationId, "expression_point", expressionPoint);
    }


    public void evaluationFailed(String situationId, String tenancyId) {
        counter("ras.engine.evaluation.failed",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public Object startProcessTimer() {
        return startTimer();
    }

    public void stopProcessTimer(Object sample, String situationId, String tenancyId) {
        stopTimer(sample, "ras.evaluator.process_time",
                  "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public void decision(String situationId, String tenancyId, TriggerDecision decision) {
        counter("ras.evaluator.decision",
                "situation_id", situationId, "tenancy_id", tenancyId,
                "decision", decision.name().toLowerCase());
    }

    public void conflictRetry(String situationId, String tenancyId) {
        counter("ras.evaluator.conflict_retries",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public void retriesExhausted(String situationId, String tenancyId) {
        counter("ras.evaluator.retries_exhausted",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public void contextExpired(String situationId, String tenancyId) {
        counter("ras.evaluator.context_expired",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public void ganglionDetectFailed(String ganglionId, String situationId) {
        counter("ras.evaluator.ganglion.detect_failed",
                "ganglion_id", ganglionId, "situation_id", situationId);
    }

    public void ganglionCompactFailed(String ganglionId, String situationId) {
        counter("ras.evaluator.ganglion.compact_failed",
                "ganglion_id", ganglionId, "situation_id", situationId);
    }

    public void ganglionCloseFailed(String ganglionId, String situationId) {
        counter("ras.evaluator.ganglion.close_failed",
                "ganglion_id", ganglionId, "situation_id", situationId);
    }

    public void triggerClaimed(String situationId, String tenancyId) {
        counter("ras.evaluator.trigger.claimed",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public void triggerRaceLost(String situationId, String tenancyId) {
        counter("ras.evaluator.trigger.race_lost",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public Object startTriggerFireTimer() {
        return startTimer();
    }

    public void stopTriggerFireTimer(Object sample, String situationId,
                                     String tenancyId, String triggerAction) {
        stopTimer(sample, "ras.evaluator.trigger.fire_time",
                  "situation_id", situationId, "tenancy_id", tenancyId,
                  "trigger_action", triggerAction);
    }

    public void triggerFired(String situationId, String tenancyId, String triggerAction) {
        counter("ras.evaluator.trigger.fired",
                "situation_id", situationId, "tenancy_id", tenancyId,
                "trigger_action", triggerAction);
    }

    public void triggerFailed(String situationId, String tenancyId, String triggerAction) {
        counter("ras.evaluator.trigger.failed",
                "situation_id", situationId, "tenancy_id", tenancyId,
                "trigger_action", triggerAction);
    }

    public void situationSuppressed(String situationId, String tenancyId) {
        counter("ras.engine.situations.suppressed",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }


    public void eventBuffered(String situationId, String tenancyId) {
        counter("ras.evaluator.buffer.events_buffered",
                "situation_id", situationId, "tenancy_id", tenancyId);
    }

    public void triggeredCleaned(int count) {
        counterBy("ras.expiry.triggered_cleaned", count);
    }

    public void expiredCleaned(int count) {
        counterBy("ras.expiry.expired_cleaned", count);
    }

    public void orphanedResourcesCleaned(int count, String cleanerType) {
        counterBy("ras.expiry.orphans_cleaned", count, "cleaner_type", cleanerType);
    }

    public void eventLogCleaned(int count) {
        counterBy("ras.expiry.event_log_cleaned", count);
    }


    public void registerActiveBuffersGauge(Supplier<Number> supplier) {
        if (metrics != null) {
            metrics.gauge("ras.evaluator.buffers.active", List.of(), supplier,
                          s -> s.get().doubleValue());
        }
    }

    private void counter(String name, String... tags) {
        if (metrics != null) {
            metrics.counter(name, tags).increment();
        }
    }

    private void counterBy(String name, double amount, String... tags) {
        if (metrics != null) {
            metrics.counter(name, tags).increment(amount);
        }
    }

    private Object startTimer() {
        return metrics != null ? Timer.start(metrics) : null;
    }

    private void stopTimer(Object sample, String name, String... tags) {
        if (sample != null) {
            ((Timer.Sample) sample).stop(metrics.timer(name, tags));
        }
    }
}
