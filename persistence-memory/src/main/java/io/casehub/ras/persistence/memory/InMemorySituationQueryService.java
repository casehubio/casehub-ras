package io.casehub.ras.persistence.memory;

import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationEvent;
import io.casehub.ras.api.SituationEventRetention;
import io.casehub.ras.api.SituationQueryService;
import io.casehub.ras.api.SituationSummary;
import io.casehub.ras.api.TenantHealth;
import io.casehub.ras.api.TrendResult;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Alternative;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@ApplicationScoped
@Alternative
@Priority(100)
public class InMemorySituationQueryService implements SituationQueryService, SituationEventRetention {

    private final CopyOnWriteArrayList<SituationEvent> events = new CopyOnWriteArrayList<>();

    void onSituationChange(@ObservesAsync SituationChangeEvent changeEvent) {
        Instant eventTime = changeEvent.context().lastSignal();
        record(SituationEvent.from(changeEvent, eventTime));
    }

    void record(SituationEvent event) {
        events.add(event);
    }

    @Override
    public List<SituationEvent> history(String tenancyId, Instant from, Instant to) {
        return events.stream()
                .filter(e -> e.tenancyId().equals(tenancyId))
                .filter(e -> inRange(e.eventTime(), from, to))
                .sorted(Comparator.comparing(SituationEvent::eventTime))
                .toList();
    }

    @Override
    public List<SituationEvent> history(String tenancyId, String situationId,
                                         Instant from, Instant to) {
        return events.stream()
                .filter(e -> e.tenancyId().equals(tenancyId))
                .filter(e -> e.situationId().equals(situationId))
                .filter(e -> inRange(e.eventTime(), from, to))
                .sorted(Comparator.comparing(SituationEvent::eventTime))
                .toList();
    }

    @Override
    public List<SituationEvent> history(String tenancyId, String situationId,
                                         String correlationKey, Instant from, Instant to) {
        return events.stream()
                .filter(e -> e.tenancyId().equals(tenancyId))
                .filter(e -> e.situationId().equals(situationId))
                .filter(e -> e.correlationKey().equals(correlationKey))
                .filter(e -> inRange(e.eventTime(), from, to))
                .sorted(Comparator.comparing(SituationEvent::eventTime))
                .toList();
    }

    @Override
    public long triggerCount(String tenancyId, String situationId,
                              Instant from, Instant to) {
        return events.stream()
                .filter(e -> e.tenancyId().equals(tenancyId))
                .filter(e -> e.situationId().equals(situationId))
                .filter(e -> e.changeType() == SituationChangeEvent.ChangeType.TRIGGERED)
                .filter(e -> inRange(e.eventTime(), from, to))
                .count();
    }

    @Override
    public TrendResult trend(String tenancyId, String situationId,
                              Duration window, Duration baseline, Instant asOf) {
        Instant windowStart   = asOf.minus(window);
        Instant baselineStart = windowStart.minus(baseline);

        long currentCount  = countTriggered(tenancyId, situationId, windowStart, asOf);
        long baselineCount = countTriggered(tenancyId, situationId, baselineStart, windowStart);

        return TrendResult.compute(currentCount, baselineCount, window, baseline);}

    @Override
    public TenantHealth health(String tenancyId, Duration window, Instant asOf) {
        Instant windowStart = asOf.minus(window);

        List<SituationEvent> inWindow = events.stream()
                .filter(e -> e.tenancyId().equals(tenancyId))
                .filter(e -> inRange(e.eventTime(), windowStart, asOf))
                .toList();

        List<SituationSummary> summaries = inWindow.stream()
                .collect(Collectors.groupingBy(SituationEvent::situationId))
                .entrySet().stream()
                .map(entry -> {
                    String sitId = entry.getKey();
                    List<SituationEvent> sitEvents = entry.getValue();
                    long triggerCount = sitEvents.stream()
                            .filter(e -> e.changeType() == SituationChangeEvent.ChangeType.TRIGGERED)
                            .count();
                    Instant lastEvent = sitEvents.stream()
                            .map(SituationEvent::eventTime)
                            .max(Comparator.naturalOrder())
                            .orElseThrow();
                    return new SituationSummary(sitId, sitEvents.size(), triggerCount, lastEvent);
                })
                .toList();

        return new TenantHealth(tenancyId, windowStart, asOf, inWindow.size(), summaries);
    }

    @Override
    public int removeEventsBefore(Instant cutoff) {
        int[] count = {0};
        events.removeIf(e -> {
            if (e.eventTime().isBefore(cutoff)) {
                count[0]++;
                return true;
            }
            return false;
        });
        return count[0];
    }


    private long countTriggered(String tenancyId, String situationId,
                                 Instant from, Instant to) {
        return events.stream()
                .filter(e -> e.tenancyId().equals(tenancyId))
                .filter(e -> e.situationId().equals(situationId))
                .filter(e -> e.changeType() == SituationChangeEvent.ChangeType.TRIGGERED)
                .filter(e -> inRange(e.eventTime(), from, to))
                .count();
    }

    private static boolean inRange(Instant time, Instant from, Instant to) {
        return !time.isBefore(from) && time.isBefore(to);
    }
}
