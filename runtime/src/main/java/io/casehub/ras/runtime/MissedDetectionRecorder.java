package io.casehub.ras.runtime;

import io.casehub.ras.api.FeedbackConfig;
import io.casehub.ras.api.MissedDetectionRecord;
import io.casehub.ras.api.OutcomeLedger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class MissedDetectionRecorder {

    private static final Duration FUTURE_SKEW_TOLERANCE = Duration.ofSeconds(30);

    private final OutcomeLedger ledger;
    private final SituationDefinitionRegistry registry;
    private final RasMetrics metrics;

    @Inject
    public MissedDetectionRecorder(OutcomeLedger ledger,
                                    SituationDefinitionRegistry registry,
                                    RasMetrics metrics) {
        this.ledger = ledger;
        this.registry = registry;
        this.metrics = metrics;
    }

    MissedDetectionRecorder(OutcomeLedger ledger,
                            SituationDefinitionRegistry registry) {
        this(ledger, registry, null);
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
        return RecordResult.accepted(isNew);
    }

    public record RecordResult(boolean accepted, boolean isNew, String rejectionReason) {
        static RecordResult accepted(boolean isNew) { return new RecordResult(true, isNew, null); }
        static RecordResult rejected(String reason) { return new RecordResult(false, false, reason); }
    }
}
