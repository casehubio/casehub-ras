package io.casehub.ras.api;

import io.smallrye.mutiny.Uni;
import java.util.List;

public interface SituationSource {
    Uni<List<ActiveSituation>> activeSituations(String tenancyId);
}
