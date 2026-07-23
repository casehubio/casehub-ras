package io.casehub.ras.runtime;

import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationSource;
import io.casehub.ras.api.SituationStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.OptionalDouble;

@ApplicationScoped
public class DefaultSituationSource implements SituationSource {

    private final SituationStore store;

    @Inject
    public DefaultSituationSource(SituationStore store) {
        this.store = store;
    }

    @Override
    public List<ActiveSituation> activeSituations(String tenancyId) {
        return store.findActive(tenancyId).stream()
                    .map(this::toActiveSituation)
                    .toList();
    }

    private ActiveSituation toActiveSituation(SituationContext context) {
        // Find the detection with max qualifying confidence
        OptionalDouble maxQualifying = context.detections().stream()
                .filter(td -> td.result().signal().isAtLeast(DetectionSignal.WEAK))
                .mapToDouble(td -> td.result().confidence())
                .max();

        double confidence = maxQualifying.orElse(0.0);

        // Extract evidence from the detection with max qualifying confidence
        var evidenceMap = context.detections().stream()
                .filter(td -> td.result().signal().isAtLeast(DetectionSignal.WEAK))
                .filter(td -> td.result().confidence() == confidence)
                .findFirst()
                .map(td -> td.result().evidence())
                .orElse(java.util.Map.of());

        return new ActiveSituation(
                context.situationId(),
                context.correlationKey(),
                context.tenancyId(),
                confidence,
                evidenceMap,
                context.firstSignal(),
                context.lastSignal(),
                context.triggerCount()
        );
    }
}
