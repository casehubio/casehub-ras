package io.casehub.ras.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface SituationQueryService {

    List<SituationEvent> history(String tenancyId, Instant from, Instant to);

    List<SituationEvent> history(String tenancyId, String situationId,
                                 Instant from, Instant to);

    List<SituationEvent> history(String tenancyId, String situationId,
                                 String correlationKey, Instant from, Instant to);

    long triggerCount(String tenancyId, String situationId,
                      Instant from, Instant to);

    TrendResult trend(String tenancyId, String situationId,
                      Duration window, Duration baseline, Instant asOf);

    TenantHealth health(String tenancyId, Duration window, Instant asOf);
}
