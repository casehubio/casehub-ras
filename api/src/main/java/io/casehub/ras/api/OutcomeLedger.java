package io.casehub.ras.api;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface OutcomeLedger {

    void record(OutcomeRecord record);

    boolean recordMissed(MissedDetectionRecord record);


    OutcomeStatistics statistics(String situationId, String tenancyId, Instant since);

    Optional<Instant> lastNoiseDismissalTime(String situationId, String correlationKey,
                                              String tenancyId);

    Map<String, Long> countByLabel(String situationId, String tenancyId, Instant since);

    Map<String, GanglionOutcomeStatistics> ganglionStatistics(String situationId,
                                                              String tenancyId, Instant since);


    Set<String> distinctTenancies(String situationId);

    int removeRecordsBefore(String situationId, Instant cutoff);
}
