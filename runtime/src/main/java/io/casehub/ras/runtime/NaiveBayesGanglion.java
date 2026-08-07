package io.casehub.ras.runtime;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.Ganglion;
import io.casehub.ras.api.GanglionState;
import io.casehub.ras.api.GanglionStateConflictException;
import io.casehub.ras.api.GanglionStateKey;
import io.casehub.ras.api.GanglionStateStore;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.TimestampedDetection;
import io.cloudevents.CloudEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

public class NaiveBayesGanglion implements Ganglion {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(NaiveBayesGanglion.class.getName());

    private static final int MAX_STATE_RETRIES = 3;

    private final NaiveBayesConfig                              config;
    private final GanglionStateStore                            stateStore;
    private final io.micrometer.core.instrument.MeterRegistry   meterRegistry;
    private final FeedbackState                                 feedbackState;
    private final double[]                                      logPriors;
    private final int                                           targetIndex;

    public NaiveBayesGanglion(NaiveBayesConfig config, GanglionStateStore stateStore,
                               io.micrometer.core.instrument.MeterRegistry meterRegistry,
                               FeedbackState feedbackState) {
        this.config        = config;
        this.stateStore    = stateStore;
        this.meterRegistry = meterRegistry;
        this.feedbackState = feedbackState;
        this.logPriors     = Arrays.stream(config.priors()).map(Math::log).toArray();
        this.targetIndex   = config.outcomes().indexOf(config.signalMapping().targetOutcome());
    }

    public NaiveBayesGanglion(NaiveBayesConfig config, GanglionStateStore stateStore,
                               io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this(config, stateStore, meterRegistry, null);
    }

    private static double[] normalizeLogPosteriors(double[] logP) {
        double max = logP[0];
        for (int i = 1; i < logP.length; i++) {
            if (logP[i] > max) {max = logP[i];}
        }
        double[] exp = new double[logP.length];
        double   sum = 0;
        for (int i = 0; i < logP.length; i++) {
            exp[i] = Math.exp(logP[i] - max);
            sum += exp[i];
        }
        for (int i = 0; i < exp.length; i++) {
            exp[i] /= sum;
        }
        return exp;
    }

    @Override
    public String ganglionId() {return config.ganglionId();}

    @Override
    public Set<String> handledEventTypes() {return config.handledEventTypes();}

    @Override
    public DetectionResult detect(CloudEvent event, SituationContext context) {
        var key = new GanglionStateKey(config.ganglionId(), context.situationId(),
                                       context.correlationKey(), context.tenancyId());

        for (int attempt = 0; attempt <= MAX_STATE_RETRIES; attempt++) {
            GanglionState loaded = stateStore.load(key)
                                             .orElseGet(() -> {
                                                 double[] initialPriors = feedbackState != null
                                                         ? feedbackState.adjustedLogPriors(config.ganglionId(), context.tenancyId())
                                                             .orElse(Arrays.copyOf(logPriors, logPriors.length))
                                                         : Arrays.copyOf(logPriors, logPriors.length);
                                                 return new GanglionState(initialPriors, OptionalLong.empty());
                                             });

            double[] logPosteriors = Arrays.copyOf(loaded.values(), loaded.values().length);

            Map<String, String> observed = config.featureExtractor().extract(event);
            for (var entry : observed.entrySet()) {
                FeatureLikelihood fl = config.features().get(entry.getKey());
                if (fl == null) {continue;}
                int valueIndex = fl.values().indexOf(entry.getValue());
                if (valueIndex < 0) {continue;}
                for (int i = 0; i < logPosteriors.length; i++) {
                    logPosteriors[i] += Math.log(fl.likelihoods()[i][valueIndex]);
                }
            }

            try {
                stateStore.save(key, new GanglionState(logPosteriors, loaded.storeVersion()));
            } catch (GanglionStateConflictException e) {
                if (attempt == MAX_STATE_RETRIES) {throw e;}
                continue;
            }

            double[] posteriors      = normalizeLogPosteriors(logPosteriors);
            double   targetPosterior = posteriors[targetIndex];

            DetectionSignal         signal;
            double                  confidence;
            NaiveBayesSignalMapping mapping = config.signalMapping();

            if (targetPosterior >= mapping.detectedThreshold()) {
                signal     = DetectionSignal.DETECTED;
                confidence = targetPosterior;
            } else if (targetPosterior >= mapping.weakThreshold()) {
                signal     = DetectionSignal.WEAK;
                confidence = targetPosterior;
            } else if (mapping.antiThreshold() != null
                       && targetPosterior <= mapping.antiThreshold()) {
                signal     = DetectionSignal.ANTI;
                confidence = 1.0 - targetPosterior;
            } else {
                signal     = DetectionSignal.NOISE;
                confidence = 0.0;
            }

            int winnerIndex = 0;
            for (int i = 1; i < posteriors.length; i++) {
                if (posteriors[i] > posteriors[winnerIndex]) {winnerIndex = i;}
            }
            String winningOutcome = config.outcomes().get(winnerIndex);

            Map<String, Object> evidence = new java.util.LinkedHashMap<>();
            evidence.put("posterior", targetPosterior);
            evidence.put("features", Map.copyOf(observed));
            evidence.put("winningOutcome", winningOutcome);

            var outcomeTemplates = config.outcomeEvidenceTemplates().get(winningOutcome);
            if (outcomeTemplates != null && !outcomeTemplates.isEmpty()) {
                Map<String, Object> exprCtx = CloudEventExpressionContext.build(event);
                for (var entry : outcomeTemplates.entrySet()) {
                    try {
                        Object value = entry.getValue().eval(exprCtx);
                        if (value != null) {
                            evidence.put(entry.getKey(), value);
                        }
                    } catch (Exception e) {
                        LOG.warning("Outcome '" + winningOutcome + "' evidence template '"
                                    + entry.getKey() + "' failed for ganglion '"
                                    + config.ganglionId() + "': " + e.getMessage());
                        if (meterRegistry != null) {
                            meterRegistry.counter("ras.expression.error",
                                                  "ganglion_id", config.ganglionId(),
                                                  "evidence_key", entry.getKey(),
                                                  "outcome", winningOutcome,
                                                  "expression_point", "outcome_evidence_extraction").increment();
                        }
                    }
                }
            }

            return new DetectionResult(config.ganglionId(), confidence, signal, evidence);
        }
        throw new IllegalStateException("Exhausted retries without success or conflict");
    }

    @Override
    public SituationContext compact(SituationContext context) {
        TimestampedDetection       latest = null;
        List<TimestampedDetection> kept   = new ArrayList<>();
        for (TimestampedDetection td : context.detections()) {
            if (td.result().ganglionId().equals(config.ganglionId())) {
                latest = td;
            } else {
                kept.add(td);
            }
        }
        if (latest == null) {
            return context;
        }
        kept.add(latest);
        return new SituationContext(
                context.situationId(), context.correlationKey(), context.tenancyId(),
                context.firstSignal(), context.lastSignal(), kept, context.storeVersion(),
                context.lastTriggered(), context.triggerCount());
    }

    @Override
    public void close(String situationId, String correlationKey, String tenancyId) {
        stateStore.remove(new GanglionStateKey(config.ganglionId(), situationId,
                                               correlationKey, tenancyId));
    }
}
