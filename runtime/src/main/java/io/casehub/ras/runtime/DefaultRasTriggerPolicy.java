package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Comparator;
import java.util.List;

@DefaultBean
@ApplicationScoped
public class DefaultRasTriggerPolicy implements RasTriggerPolicy {

    @Override
    public Uni<TriggerDecision> evaluate(SituationContext context, SituationDefinition definition) {
        boolean satisfied = switch (definition.chainMode()) {
            case ChainMode.And and -> evaluateAnd(context, and);
            case ChainMode.Or or -> evaluateOr(context, or);
            case ChainMode.Threshold threshold -> evaluateThreshold(context, threshold);
            case ChainMode.Sequence sequence -> evaluateSequence(context, sequence);
            case ChainMode.Count count -> evaluateCount(context, count);
        };
        return Uni.createFrom().item(satisfied ? TriggerDecision.CREATE_CASE
                                               : TriggerDecision.CONTINUE_ACCUMULATING);
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

    private long countQualifying(SituationContext ctx, String ganglionId) {
        return ctx.detections().stream()
                .filter(td -> td.result().ganglionId().equals(ganglionId))
                .filter(td -> td.result().signal().isAtLeast(DetectionSignal.WEAK))
                .count();
    }
}
