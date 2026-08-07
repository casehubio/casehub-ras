package io.casehub.ras.runtime;

import io.casehub.ras.api.OutcomeStatistics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class FeedbackMetrics {

    private final MeterRegistry meterRegistry;

    @Inject
    public FeedbackMetrics(Instance<MeterRegistry> meterRegistryInstance) {
        this.meterRegistry = meterRegistryInstance != null && meterRegistryInstance.isResolvable()
                ? meterRegistryInstance.get() : null;
    }

    FeedbackMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordStatistics(String situationId, String tenancyId, OutcomeStatistics stats) {
        if (meterRegistry == null) return;

        Tags tags = Tags.of("situation_id", situationId, "tenancy_id", tenancyId);

        meterRegistry.gauge("ras.feedback.outcomes_total", tags, stats, OutcomeStatistics::totalOutcomes);

        double precision = stats.precision();
        if (!Double.isNaN(precision)) {
            meterRegistry.gauge("ras.feedback.precision", tags, precision, v -> v);
        }

        double noiseRate = stats.noiseRate();
        if (!Double.isNaN(noiseRate)) {
            meterRegistry.gauge("ras.feedback.noise_rate", tags, noiseRate, v -> v);
        }
    }

    public void thresholdAdjusted(String situationId, String tenancyId, double newThreshold) {
        if (meterRegistry == null) return;
        meterRegistry.counter("ras.feedback.threshold_adjustments_total",
                "situation_id", situationId, "tenancy_id", tenancyId).increment();
    }

    public void priorsAdjusted(String ganglionId, String tenancyId) {
        if (meterRegistry == null) return;
        meterRegistry.counter("ras.feedback.prior_adjustments_total",
                "ganglion_id", ganglionId, "tenancy_id", tenancyId).increment();
    }

    public void retentionCleanup(String situationId, int removed) {
        if (meterRegistry == null) return;
        meterRegistry.counter("ras.feedback.retention_cleaned_total",
                "situation_id", situationId).increment(removed);
    }
}
