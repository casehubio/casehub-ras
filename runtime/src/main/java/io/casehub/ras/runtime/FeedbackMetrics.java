package io.casehub.ras.runtime;

import io.casehub.ras.api.GanglionOutcomeStatistics;
import io.casehub.ras.api.OutcomeStatistics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class FeedbackMetrics {

    private final MeterRegistry                                      meterRegistry;
    private final ConcurrentHashMap<String, AtomicReference<Double>> gaugeHolders =
            new ConcurrentHashMap<>();

    @Inject
    public FeedbackMetrics(Instance<MeterRegistry> meterRegistryInstance) {
        this.meterRegistry = meterRegistryInstance != null && meterRegistryInstance.isResolvable()
                             ? meterRegistryInstance.get() : null;
    }

    FeedbackMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordStatistics(String situationId, String tenancyId, OutcomeStatistics stats) {
        Tags tags = Tags.of("situation_id", situationId, "tenancy_id", tenancyId);
        setGauge("ras.feedback.outcomes_total", tags, stats.totalOutcomes());
        setGauge("ras.feedback.precision", tags, stats.precision());
        setGauge("ras.feedback.noise_rate", tags, stats.noiseRate());
        setGauge("ras.feedback.recall", tags, stats.recall());
    }

    public void recordGanglionStatistics(String ganglionId, String situationId,
                                         String tenancyId, GanglionOutcomeStatistics stats) {
        Tags tags = Tags.of("ganglion_id", ganglionId, "situation_id", situationId,
                            "tenancy_id", tenancyId);
        setGauge("ras.feedback.ganglion.precision", tags, stats.precision());
        setGauge("ras.feedback.ganglion.noise_rate", tags, stats.noiseRate());
    }

    public void thresholdAdjusted(String situationId, String tenancyId, double newThreshold) {
        if (meterRegistry == null) {return;}
        meterRegistry.counter("ras.feedback.threshold_adjustments_total",
                              "situation_id", situationId, "tenancy_id", tenancyId).increment();
    }

    public void priorsAdjusted(String ganglionId, String tenancyId) {
        if (meterRegistry == null) {return;}
        meterRegistry.counter("ras.feedback.prior_adjustments_total",
                              "ganglion_id", ganglionId, "tenancy_id", tenancyId).increment();
    }

    public void retentionCleanup(String situationId, int removed) {
        if (meterRegistry == null) {return;}
        meterRegistry.counter("ras.feedback.retention_cleaned_total",
                              "situation_id", situationId).increment(removed);
    }

    private void setGauge(String name, Tags tags, double value) {
        if (meterRegistry == null || Double.isNaN(value)) {return;}
        String key = name + "|" + tags.stream()
                                      .map(t -> t.getKey() + "=" + t.getValue())
                                      .collect(java.util.stream.Collectors.joining(","));
        AtomicReference<Double> holder = gaugeHolders.computeIfAbsent(key, k -> {
            AtomicReference<Double> ref = new AtomicReference<>(value);
            meterRegistry.gauge(name, tags, ref, AtomicReference::get);
            return ref;
        });
        holder.set(value);
    }
}
