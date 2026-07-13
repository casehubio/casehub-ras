package io.casehub.ras.drools.reliability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.function.Supplier;

@ApplicationScoped
public class DroolsReliabilityMetrics {

    @Inject
    Instance<MeterRegistry> meterRegistryInstance;

    private MeterRegistry metrics;

    void setMeterRegistry(MeterRegistry registry) {
        this.metrics = registry;
    }

    @PostConstruct
    void init() {
        if (metrics == null && meterRegistryInstance != null && meterRegistryInstance.isResolvable()) {
            metrics = meterRegistryInstance.get();
        }
    }

    public void registerActiveSessionsGauge(Supplier<Number> supplier) {
        if (metrics != null) {
            metrics.gauge("ras.drools.session.active", List.of(), supplier,
                    s -> s.get().doubleValue());
        }
    }

    public Object startComputeTimer() {
        return metrics != null ? Timer.start(metrics) : null;
    }

    public void stopComputeTimer(Object sample, String ganglionId, String outcome) {
        if (sample != null) {
            ((Timer.Sample) sample).stop(metrics.timer("ras.drools.session.compute_time",
                    "ganglion_id", ganglionId, "outcome", outcome));
        }
    }

    public void sessionCreated(String ganglionId) {
        counter("ras.drools.session.created", ganglionId);
    }

    public void sessionEvicted(String ganglionId) {
        counter("ras.drools.session.evicted", ganglionId);
    }

    public void sessionRecovered(String ganglionId) {
        counter("ras.drools.session.recovered", ganglionId);
    }

    public void sessionRecoveryFailed(String ganglionId) {
        counter("ras.drools.session.recovery_failed", ganglionId);
    }

    public void sessionRemoved(String ganglionId) {
        counter("ras.drools.session.removed", ganglionId);
    }

    public void storeWriteFailed(String ganglionId) {
        counter("ras.drools.store.write_failed", ganglionId);
    }

    public void storeCorruptionRecovered() {
        if (metrics != null) {
            metrics.counter("ras.drools.store.corruption_recovered").increment();
        }
    }


    private void counter(String name, String ganglionId) {
        if (metrics != null) {
            metrics.counter(name, "ganglion_id", ganglionId).increment();
        }
    }
}
