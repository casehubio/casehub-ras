package io.casehub.ras.runtime;

import io.casehub.ras.api.OutcomeClassification;
import io.casehub.ras.api.OutcomeLedger;
import io.casehub.ras.api.OutcomeRecord;
import io.casehub.ras.api.OutcomeStatistics;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
    public OutcomeStatistics statistics(String situationId, String tenancyId, Instant since) {
        List<OutcomeRecord> records = store.getOrDefault(key(situationId, tenancyId), List.of());
        long noise = 0, confirmed = 0, neutral = 0;
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
        return new OutcomeStatistics(situationId, tenancyId,
                noise + confirmed + neutral, noise, confirmed, neutral, since);
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
    public Set<String> distinctTenancies(String situationId) {
        String prefix = situationId + "|";
        return store.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .collect(Collectors.toSet());
    }

    @Override
    public int removeRecordsBefore(String situationId, Instant cutoff) {
        int removed = 0;
        String prefix = situationId + "|";
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
        return removed;
    }
}
