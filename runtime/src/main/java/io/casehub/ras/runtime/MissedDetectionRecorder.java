package io.casehub.ras.runtime;

import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.MissedDetectionRecord;
import io.casehub.ras.api.OutcomeLedger;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationEvent;
import io.casehub.ras.api.SituationQueryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class MissedDetectionRecorder {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(MissedDetectionRecorder.class.getName());
    private static final Duration FUTURE_SKEW_TOLERANCE = Duration.ofSeconds(30);

    private final OutcomeLedger ledger;
    private final SituationDefinitionRegistry registry;
    private final RasMetrics metrics;
    private final Instance<SituationQueryService> queryServiceInstance;
    private final Duration eventHistoryRetention;

    @Inject
    public MissedDetectionRecorder(OutcomeLedger ledger,
                                    SituationDefinitionRegistry registry,
                                    RasMetrics metrics,
                                    Instance<SituationQueryService> queryServiceInstance,
                                    @ConfigProperty(name = "ras.event-history.retention",
                                                    defaultValue = "P30D")
                                    Duration eventHistoryRetention) {
        this.ledger = ledger;
        this.registry = registry;
        this.metrics = metrics;
        this.queryServiceInstance = queryServiceInstance;
        this.eventHistoryRetention = eventHistoryRetention;
    }

    MissedDetectionRecorder(OutcomeLedger ledger,
                            SituationDefinitionRegistry registry) {
        this.ledger = ledger;
        this.registry = registry;
        this.metrics = null;
        this.queryServiceInstance = null;
        this.eventHistoryRetention = Duration.ofDays(30);
    }

    public RecordResult record(MissedDetectionRecord record) {
        if (!registry.exists(record.situationId())) {
            if (metrics != null) metrics.missedRejected(record.situationId(), "UNKNOWN_SITUATION");
            return RecordResult.rejected("UNKNOWN_SITUATION");
        }
        FeedbackConfig config = registry.feedbackConfig(record.situationId());
        if (config == null) {
            if (metrics != null) metrics.missedRejected(record.situationId(), "FEEDBACK_NOT_CONFIGURED");
            return RecordResult.rejected("FEEDBACK_NOT_CONFIGURED");
        }
        if (record.ganglionIds() != null && !record.ganglionIds().isEmpty()) {
            var definition = registry.definition(record.situationId());
            if (definition != null) {
                java.util.Set<String> validGanglia = definition.chainMode().referencedGanglia();
                for (String gid : record.ganglionIds()) {
                    if (!validGanglia.contains(gid)) {
                        if (metrics != null) metrics.missedRejected(record.situationId(), "UNKNOWN_GANGLION");
                        return RecordResult.rejected("UNKNOWN_GANGLION");
                    }
                }
            }
        }
        Instant now = Instant.now();
        Instant windowStart = now.minus(config.retentionPeriod());
        if (record.eventTime().isBefore(windowStart)
                || record.eventTime().isAfter(now.plus(FUTURE_SKEW_TOLERANCE))) {
            if (metrics != null) metrics.missedRejected(record.situationId(), "EVENT_OUTSIDE_WINDOW");
            return RecordResult.rejected("EVENT_OUTSIDE_WINDOW");
        }
        boolean isNew = ledger.recordMissed(record);
        if (isNew && metrics != null) {
            metrics.missedRecorded(record.situationId(), record.tenancyId());
        }

        boolean possiblyDetected = false;
        Instant lastTriggerTime = null;
        boolean crossRefConclusive = false;

        if (queryServiceInstance != null && queryServiceInstance.isResolvable()) {
            Instant historyWindowStart = now.minus(eventHistoryRetention);
            if (!record.eventTime().isBefore(historyWindowStart)) {
                try {
                    crossRefConclusive = true;
                    Duration crossRefWindow = config.crossRefWindow();
                    List<SituationEvent> history = queryServiceInstance.get().history(
                            record.tenancyId(), record.situationId(), record.correlationKey(),
                            record.eventTime().minus(crossRefWindow),
                            record.eventTime().plus(crossRefWindow));
                    for (SituationEvent event : history) {
                        if (event.changeType() == SituationChangeEvent.ChangeType.TRIGGERED) {
                            possiblyDetected = true;
                            lastTriggerTime = event.eventTime();
                            break;
                        }
                    }
                } catch (RuntimeException ex) {
                    crossRefConclusive = false;
                    LOG.warning("Cross-reference query failed for situation '"
                                + record.situationId() + "': " + ex.getMessage());
                }
            }
        }
        return RecordResult.accepted(isNew, possiblyDetected, lastTriggerTime, crossRefConclusive);
    }

    public record RecordResult(boolean accepted, boolean isNew, String rejectionReason,
                                boolean possiblyDetected, Instant lastTriggerTime,
                                boolean crossRefConclusive) {
        static RecordResult accepted(boolean isNew) {
            return new RecordResult(true, isNew, null, false, null, false);
        }
        static RecordResult accepted(boolean isNew, boolean possiblyDetected,
                                      Instant lastTriggerTime, boolean crossRefConclusive) {
            return new RecordResult(true, isNew, null, possiblyDetected,
                                    lastTriggerTime, crossRefConclusive);
        }
        static RecordResult rejected(String reason) {
            return new RecordResult(false, false, reason, false, null, false);
        }
    }
}
