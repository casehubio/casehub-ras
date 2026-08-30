package io.casehub.ras.runtime;

import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.GanglionContribution;
import io.casehub.ras.api.GanglionOutcomeStatistics;
import io.casehub.ras.api.MissedDetectionRecord;
import io.casehub.ras.api.OutcomeClassification;
import io.casehub.ras.api.OutcomeLedger;
import io.casehub.ras.api.OutcomeRecord;
import io.casehub.ras.api.OutcomeStatistics;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
@DefaultBean
public class InMemoryOutcomeLedger implements OutcomeLedger {

    private final ConcurrentHashMap<String, List<OutcomeRecord>> store = new ConcurrentHashMap<>();
    private final Set<UUID> seenCaseIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<MissedDetectionKey, MissedDetectionRecord> missedStore = new ConcurrentHashMap<>();
    private final Set<UUID> seenReportIds = ConcurrentHashMap.newKeySet();

    private record MissedDetectionKey(String situationId, String correlationKey, String tenancyId, Instant eventTime) {}


    private static String key(String situationId, String tenancyId) {
        return situationId + "|" + tenancyId;
    }

    @Override
    public void record(OutcomeRecord record) {
        if (!seenCaseIds.add(record.caseId())) return;
        store.computeIfAbsent(key(record.situationId(), record.tenancyId()),
                k -> Collections.synchronizedList(new ArrayList<>())).add(record);
    }

    @Override
    public boolean recordMissed(MissedDetectionRecord record) {
        if (!seenReportIds.add(record.reportId())) {
            return false;
        }
        var key = new MissedDetectionKey(record.situationId(), record.correlationKey(),
                                         record.tenancyId(), record.eventTime());
        return missedStore.putIfAbsent(key, record) == null;
    }


    @Override
    public OutcomeStatistics statistics(String situationId, String tenancyId, Instant since) {
        List<OutcomeRecord> records = store.getOrDefault(key(situationId, tenancyId), List.of());
        long                noise   = 0, confirmed = 0, neutral = 0;
        synchronized (records) {
            for (OutcomeRecord r : records) {
                if (!r.closedAt().isBefore(since)) {
                    switch (r.classification()) {
                        case NOISE -> noise++;
                        case CONFIRMED -> confirmed++;
                        case NEUTRAL -> neutral++;
                    }
                }
            }
        }
        long missedCount = missedStore.values().stream()
                                      .filter(r -> r.situationId().equals(situationId)
                                                   && r.tenancyId().equals(tenancyId)
                                                   && !r.eventTime().isBefore(since))
                                      .count();
        return new OutcomeStatistics(situationId, tenancyId,
                                     noise + confirmed + neutral, noise, confirmed, neutral, since, missedCount);
    }

    @Override
    public Optional<Instant> lastNoiseDismissalTime(String situationId,
            String correlationKey, String tenancyId) {
        List<OutcomeRecord> records = store.getOrDefault(key(situationId, tenancyId), List.of());
        synchronized (records) {
            return records.stream()
                    .filter(r -> r.correlationKey().equals(correlationKey)
                            && r.classification() == OutcomeClassification.NOISE)
                    .map(OutcomeRecord::closedAt)
                    .max(Instant::compareTo);
        }
    }

    @Override
    public Map<String, Long> countByLabel(String situationId, String tenancyId, Instant since) {
        List<OutcomeRecord> records = store.getOrDefault(key(situationId, tenancyId), List.of());
        synchronized (records) {
            return records.stream()
                    .filter(r -> !r.closedAt().isBefore(since))
                    .collect(Collectors.groupingBy(OutcomeRecord::outcomeLabel, Collectors.counting()));
        }
    }

    @Override
    public Map<String, GanglionOutcomeStatistics> ganglionStatistics(
            String situationId, String tenancyId, Instant since) {
        List<OutcomeRecord> records = store.getOrDefault(key(situationId, tenancyId), List.of());
        Map<String, long[]> counts  = new LinkedHashMap<>();
        synchronized (records) {
            for (OutcomeRecord r : records) {
                if (r.closedAt().isBefore(since)) {continue;}
                for (GanglionContribution gc : r.ganglionContributions()) {
                    if (!gc.signal().isAtLeast(DetectionSignal.WEAK)) {continue;}
                    long[] c = counts.computeIfAbsent(gc.ganglionId(), k -> new long[4]);
                    switch (r.classification()) {
                        case NOISE -> c[0]++;
                        case CONFIRMED -> c[1]++;
                        case NEUTRAL -> c[2]++;
                    }
                }
            }
        }
        for (MissedDetectionRecord r : missedStore.values()) {
            if (!r.situationId().equals(situationId) || !r.tenancyId().equals(tenancyId)
                    || r.eventTime().isBefore(since) || r.ganglionIds() == null) continue;
            for (String gid : r.ganglionIds()) {
                long[] c = counts.computeIfAbsent(gid, k -> new long[4]);
                c[3]++;
            }
        }
        Map<String, GanglionOutcomeStatistics> result = new LinkedHashMap<>();
        for (var entry : counts.entrySet()) {
            long[] c = entry.getValue();
            result.put(entry.getKey(), new GanglionOutcomeStatistics(
                    entry.getKey(), c[0] + c[1] + c[2], c[0], c[1], c[2], c[3]));
        }
        return result;
    }


    @Override
    public Set<String> distinctTenancies(String situationId) {
        String prefix = situationId + "|";
        Set<String> tenancies = store.keySet().stream()
                                     .filter(k -> k.startsWith(prefix))
                                     .map(k -> k.substring(prefix.length()))
                                     .collect(Collectors.toCollection(java.util.HashSet::new));
        missedStore.values().stream()
                   .filter(r -> r.situationId().equals(situationId))
                   .map(MissedDetectionRecord::tenancyId)
                   .forEach(tenancies::add);
        return tenancies;
    }

    @Override
    public int removeRecordsBefore(String situationId, Instant cutoff) {
        int    removed = 0;
        String prefix  = situationId + "|";
        for (var entry : store.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                List<OutcomeRecord> records = entry.getValue();
                synchronized (records) {
                    List<OutcomeRecord> toRemove = records.stream()
                                                          .filter(r -> r.closedAt().isBefore(cutoff))
                                                          .toList();
                    toRemove.forEach(r -> seenCaseIds.remove(r.caseId()));
                    records.removeAll(toRemove);
                    removed += toRemove.size();
                }
            }
        }
        var missedIt = missedStore.entrySet().iterator();
        while (missedIt.hasNext()) {
            var entry = missedIt.next();
            if (entry.getKey().situationId().equals(situationId)
                && entry.getValue().eventTime().isBefore(cutoff)) {
                missedIt.remove();
                seenReportIds.remove(entry.getValue().reportId());
                removed++;
            }
        }
        return removed;
    }
}
