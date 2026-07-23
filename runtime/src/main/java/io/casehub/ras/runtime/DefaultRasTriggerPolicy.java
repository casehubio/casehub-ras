package io.casehub.ras.runtime;

import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.PolicyDecision;
import io.casehub.ras.api.RasTriggerPolicy;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.TimestampedDetection;
import io.casehub.ras.api.TriggerDecision;
import io.casehub.ras.api.TriggerMode;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;

@DefaultBean
@ApplicationScoped
public class DefaultRasTriggerPolicy implements RasTriggerPolicy {

    @Override
    public PolicyDecision evaluate(SituationContext context, SituationDefinition definition) {
        boolean satisfied = switch (definition.chainMode()) {
            case ChainMode.And and -> evaluateAnd(context, and);
            case ChainMode.Or or -> evaluateOr(context, or);
            case ChainMode.Threshold threshold -> evaluateThreshold(context, threshold);
            case ChainMode.Sequence sequence -> evaluateSequence(context, sequence);
            case ChainMode.Count count -> evaluateCount(context, count);
            case ChainMode.Streak streak -> evaluateStreak(context, streak);
            case ChainMode.Rate rate -> evaluateRate(context, rate);
        };

        if (!satisfied) {
            return new PolicyDecision(TriggerDecision.CONTINUE_ACCUMULATING);
        }

        TriggerMode mode = definition.triggerMode();

        return new PolicyDecision(switch (mode) {
            case TriggerMode.FireOnce ignored -> TriggerDecision.TRIGGER;
            case TriggerMode.Repeating repeating -> evaluateRepeating(context, repeating);
        });
    }

    private TriggerDecision evaluateRepeating(SituationContext context, TriggerMode.Repeating repeating) {
        if (context.lastTriggered() == null) {
            // Never triggered before — cooldown elapsed
            return TriggerDecision.TRIGGER_AND_CONTINUE;
        }

        var cooldownEnd = context.lastTriggered().plus(repeating.cooldown());
        if (context.lastSignal().isAfter(cooldownEnd) || context.lastSignal().equals(cooldownEnd)) {
            // Cooldown elapsed
            return TriggerDecision.TRIGGER_AND_CONTINUE;
        } else {
            // Still in cooldown
            return TriggerDecision.CONTINUE_ACCUMULATING;
        }
    }

    private boolean evaluateAnd(SituationContext ctx, ChainMode.And and) {
        for (String ganglionId : and.requiredGanglia()) {
            if (countQualifying(ctx, ganglionId) == 0) return false;
        }
        return true;
    }

    private boolean evaluateOr(SituationContext ctx, ChainMode.Or or) {
        for (String ganglionId : or.ganglia()) {
            if (countQualifying(ctx, ganglionId) > 0) return true;
        }
        return false;
    }

    private boolean evaluateThreshold(SituationContext ctx, ChainMode.Threshold threshold) {
        double sum = ctx.detections().stream()
                .filter(td -> threshold.ganglia().contains(td.result().ganglionId()))
                .filter(td -> td.result().signal().isAtLeast(DetectionSignal.ANTI))
                .mapToDouble(td -> td.result().signal() == DetectionSignal.ANTI
                        ? -td.result().confidence()
                        : td.result().confidence())
                .sum();
        return sum >= threshold.minConfidence();
    }

    private boolean evaluateSequence(SituationContext ctx, ChainMode.Sequence sequence) {
        List<String> ordered = sequence.orderedGanglia();
        List<TimestampedDetection> sorted = ctx.detections().stream()
                .filter(td -> ordered.contains(td.result().ganglionId()))
                .filter(td -> td.result().signal().isAtLeast(DetectionSignal.WEAK))
                .sorted(Comparator.comparing(TimestampedDetection::eventTime))
                .toList();

        int seqIndex = 0;
        for (var td : sorted) {
            if (td.result().ganglionId().equals(ordered.get(seqIndex))) {
                seqIndex++;
                if (seqIndex == ordered.size()) return true;
            }
        }
        return false;
    }

    private boolean evaluateCount(SituationContext ctx, ChainMode.Count count) {
        return countQualifying(ctx, count.ganglionId()) >= count.requiredCount();
    }

    private boolean evaluateStreak(SituationContext ctx, ChainMode.Streak streak) {
        List<TimestampedDetection> filtered = ctx.detections().stream()
                .filter(td -> td.result().ganglionId().equals(streak.ganglionId()))
                .sorted(Comparator.comparing(TimestampedDetection::eventTime))
                .toList();

        int consecutive = 0;
        for (var td : filtered) {
            DetectionSignal signal = td.result().signal();
            if (signal == DetectionSignal.ANTI) {
                consecutive = 0;
            } else if (signal.isAtLeast(DetectionSignal.WEAK)) {
                consecutive++;
                if (consecutive >= streak.requiredCount()) return true;
            }
        }
        return false;
    }

    private boolean evaluateRate(SituationContext ctx, ChainMode.Rate rate) {
        List<TimestampedDetection> scoreable = ctx.detections().stream()
                .filter(td -> rate.ganglia().contains(td.result().ganglionId()))
                .filter(td -> td.result().signal().isAtLeast(DetectionSignal.ANTI))
                .sorted(Comparator.comparing(TimestampedDetection::eventTime))
                .toList();

        if (scoreable.size() < rate.windowSize()) {
            return false;
        }

        List<TimestampedDetection> window = scoreable.subList(
                scoreable.size() - rate.windowSize(), scoreable.size());

        long qualifying = window.stream()
                .filter(td -> td.result().signal().isAtLeast(DetectionSignal.WEAK))
                .count();

        return (double) qualifying / rate.windowSize() >= rate.minRate();
    }

    private long countQualifying(SituationContext ctx, String ganglionId) {
        return ctx.detections().stream()
                .filter(td -> td.result().ganglionId().equals(ganglionId))
                .filter(td -> td.result().signal().isAtLeast(DetectionSignal.WEAK))
                .count();
    }
}
