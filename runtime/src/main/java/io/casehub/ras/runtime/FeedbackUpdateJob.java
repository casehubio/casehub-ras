package io.casehub.ras.runtime;

import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.DriftDirection;
import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.FeedbackTuningStrategy;
import io.casehub.ras.api.GanglionDescriptor;
import io.casehub.ras.api.GanglionOutcomeStatistics;
import io.casehub.ras.api.OutcomeLedger;
import io.casehub.ras.api.OutcomeStatistics;
import io.casehub.ras.api.SituationDefinition;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

@ApplicationScoped
public class FeedbackUpdateJob {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(FeedbackUpdateJob.class.getName());

    private final SituationDefinitionRegistry registry;
    private final OutcomeLedger ledger;
    private final FeedbackAnalyzer analyzer;
    private final FeedbackTuningStrategy tuningStrategy;
    private final FeedbackState feedbackState;
    private final FeedbackMetrics feedbackMetrics;

    @Inject
    public FeedbackUpdateJob(SituationDefinitionRegistry registry,
                             OutcomeLedger ledger,
                             FeedbackAnalyzer analyzer,
                             FeedbackTuningStrategy tuningStrategy,
                             FeedbackState feedbackState,
                             FeedbackMetrics feedbackMetrics) {
        this.registry = registry;
        this.ledger = ledger;
        this.analyzer = analyzer;
        this.tuningStrategy = tuningStrategy;
        this.feedbackState = feedbackState;
        this.feedbackMetrics = feedbackMetrics;
    }

    @Scheduled(every = "${ras.feedback.update-interval:PT5M}",
               concurrentExecution = io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP)
    public void updateFeedback() {
        for (String situationId : registry.allSituationIds()) {
            try {
                processSituation(situationId);
            } catch (RuntimeException ex) {
                LOG.warning("Feedback update failed for situation '" + situationId + "': " + ex.getMessage());
            }
        }
    }

    private void processSituation(String situationId) {
        FeedbackConfig config = registry.feedbackConfig(situationId);
        if (config == null) return;

        SituationDefinition definition = registry.definition(situationId);

        for (String tenancyId : ledger.distinctTenancies(situationId)) {
            try {
                processTenant(situationId, tenancyId, config, definition);
            } catch (RuntimeException ex) {
                LOG.warning("Feedback update failed for situation '" + situationId
                            + "' tenant '" + tenancyId + "': " + ex.getMessage());
            }
        }

        Instant retentionCutoff = Instant.now().minus(config.retentionPeriod());
        int removed = ledger.removeRecordsBefore(situationId, retentionCutoff);
        if (removed > 0) {
            feedbackMetrics.retentionCleanup(situationId, removed);
        }
    }

    private void processTenant(String situationId, String tenancyId,
                               FeedbackConfig config, SituationDefinition definition) {
        OutcomeStatistics stats = analyzer.analyze(situationId, tenancyId, config);
        feedbackMetrics.recordStatistics(situationId, tenancyId, stats);

        Map<String, GanglionOutcomeStatistics> ganglionStats =
                analyzer.ganglionAnalyze(situationId, tenancyId, config);
        for (var entry : ganglionStats.entrySet()) {
            feedbackMetrics.recordGanglionStatistics(
                    entry.getKey(), situationId, tenancyId, entry.getValue());
        }

        DriftDirection drift = tuningStrategy.classifyDrift(stats, config);
        feedbackMetrics.setDriftGauges(drift, situationId, tenancyId);

        if (!config.tuningEnabled()) return;
        if (drift == DriftDirection.BOTH_DRIFTING) return;

        if (definition.chainMode() instanceof ChainMode.Threshold threshold) {
            double currentThreshold = feedbackState.effectiveThreshold(situationId, tenancyId)
                    .orElse(threshold.minConfidence());
            OptionalDouble adjusted = tuningStrategy.adjustThreshold(stats, currentThreshold, config);
            if (adjusted.isPresent()) {
                feedbackState.applyThresholdOverride(situationId, tenancyId, adjusted.getAsDouble());
                feedbackMetrics.thresholdAdjusted(situationId, tenancyId, adjusted.getAsDouble());
            }
        } else if (definition.chainMode() instanceof ChainMode.Rate rate) {
            double currentRate = feedbackState.effectiveThreshold(situationId, tenancyId)
                    .orElse(rate.minRate());
            OptionalDouble adjusted = tuningStrategy.adjustThreshold(stats, currentRate, config);
            if (adjusted.isPresent()) {
                feedbackState.applyThresholdOverride(situationId, tenancyId, adjusted.getAsDouble());
                feedbackMetrics.thresholdAdjusted(situationId, tenancyId, adjusted.getAsDouble());
            }
        }

        for (String ganglionId : definition.chainMode().referencedGanglia()) {
            adjustPriors(ganglionId, situationId, tenancyId, config);
        }
    }

    private void adjustPriors(String ganglionId, String situationId, String tenancyId,
                              FeedbackConfig config) {
        GanglionDescriptor descriptor = registry.ganglionDescriptor(ganglionId);
        if (!(descriptor instanceof GanglionDescriptor.NaiveBayes nb)) return;

        Map<String, String> groundTruth = nb.outcomeGroundTruth();
        if (groundTruth == null || groundTruth.isEmpty()) return;

        Map<String, Long> labelCounts = ledger.countByLabel(situationId, tenancyId,
                Instant.now().minus(config.retentionPeriod()));

        long[] outcomeCounts = new long[nb.outcomes().size()];
        for (var entry : groundTruth.entrySet()) {
            int idx = nb.outcomes().indexOf(entry.getValue());
            if (idx >= 0) {
                outcomeCounts[idx] += labelCounts.getOrDefault(entry.getKey(), 0L);
            }
        }

        double[] currentPriors = feedbackState.currentRawPriors(ganglionId, tenancyId, nb.priors());

        Optional<double[]> adjusted = tuningStrategy.adjustPriors(currentPriors, outcomeCounts, config);
        if (adjusted.isPresent()) {
            feedbackState.applyPriorOverride(ganglionId, tenancyId, adjusted.get());
            feedbackMetrics.priorsAdjusted(ganglionId, tenancyId);
        }
    }
}
