package io.casehub.ras.api;

import java.util.List;

public interface SituationSource {
    List<ActiveSituation> activeSituations(String tenancyId);
}
