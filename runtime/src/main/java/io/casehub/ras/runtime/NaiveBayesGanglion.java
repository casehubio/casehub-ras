package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.cloudevents.CloudEvent;
import io.smallrye.mutiny.Uni;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class NaiveBayesGanglion implements Ganglion {

    private record StateKey(String situationId, String correlationKey, String tenancyId) {}

    private final NaiveBayesConfig config;
    private final double[] logPriors;
    private final int targetIndex;
    private final ConcurrentHashMap<StateKey, double[]> states;

    public NaiveBayesGanglion(NaiveBayesConfig config) {
        this.config = config;
        this.logPriors = Arrays.stream(config.priors()).map(Math::log).toArray();
        this.targetIndex = config.outcomes().indexOf(config.signalMapping().targetOutcome());
        this.states = new ConcurrentHashMap<>();
    }

    @Override
    public String ganglionId() { return config.ganglionId(); }

    @Override
    public Set<String> handledEventTypes() { return config.handledEventTypes(); }

    @Override
    public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
        var key = new StateKey(context.situationId(), context.correlationKey(), context.tenancyId());
        double[] logPosteriors = states.computeIfAbsent(key,
                k -> Arrays.copyOf(logPriors, logPriors.length));

        Map<String, String> observed = config.featureExtractor().extract(event);
        for (var entry : observed.entrySet()) {
            FeatureLikelihood fl = config.features().get(entry.getKey());
            if (fl == null) continue;
            int valueIndex = fl.values().indexOf(entry.getValue());
            if (valueIndex < 0) continue;
            for (int i = 0; i < logPosteriors.length; i++) {
                logPosteriors[i] += Math.log(fl.likelihoods()[i][valueIndex]);
            }
        }

        double[] posteriors = normalizeLogPosteriors(logPosteriors);
        double targetPosterior = posteriors[targetIndex];

        DetectionSignal signal;
        double                  confidence;
        NaiveBayesSignalMapping mapping = config.signalMapping();

        if (targetPosterior >= mapping.detectedThreshold()) {
            signal = DetectionSignal.DETECTED;
            confidence = targetPosterior;
        } else if (targetPosterior >= mapping.weakThreshold()) {
            signal = DetectionSignal.WEAK;
            confidence = targetPosterior;
        } else if (mapping.antiThreshold() != null
                   && targetPosterior <= mapping.antiThreshold()) {
            signal = DetectionSignal.ANTI;
            confidence = 1.0 - targetPosterior;
        } else {
            signal = DetectionSignal.NOISE;
            confidence = 0.0;
        }

        var evidence = Map.<String, Object>of(
                "posterior", targetPosterior, "features", Map.copyOf(observed));
        return Uni.createFrom().item(
                new DetectionResult(config.ganglionId(), confidence, signal, evidence));
    }

    @Override
    public Uni<SituationContext> compact(SituationContext context) {
        TimestampedDetection latest = null;
        List<TimestampedDetection> kept = new ArrayList<>();
        for (TimestampedDetection td : context.detections()) {
            if (td.result().ganglionId().equals(config.ganglionId())) {
                latest = td;
            } else {
                kept.add(td);
            }
        }
        if (latest == null) {
            return Uni.createFrom().item(context);
        }
        kept.add(latest);
        return Uni.createFrom().item(new SituationContext(
                context.situationId(), context.correlationKey(), context.tenancyId(),
                context.firstSignal(), context.lastSignal(), kept, context.storeVersion(),
                context.lastTriggered(), context.triggerCount()));
    }

    @Override
    public Uni<Void> close(String situationId, String correlationKey, String tenancyId) {
        states.remove(new StateKey(situationId, correlationKey, tenancyId));
        return Uni.createFrom().voidItem();
    }

    private static double[] normalizeLogPosteriors(double[] logP) {
        double max = logP[0];
        for (int i = 1; i < logP.length; i++) {
            if (logP[i] > max) max = logP[i];
        }
        double[] exp = new double[logP.length];
        double sum = 0;
        for (int i = 0; i < logP.length; i++) {
            exp[i] = Math.exp(logP[i] - max);
            sum += exp[i];
        }
        for (int i = 0; i < exp.length; i++) {
            exp[i] /= sum;
        }
        return exp;
    }
}
