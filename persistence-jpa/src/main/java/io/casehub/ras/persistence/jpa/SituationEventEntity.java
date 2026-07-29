package io.casehub.ras.persistence.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ras_situation_event")
public class SituationEventEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "situation_id", nullable = false)
    private String situationId;

    @Column(name = "correlation_key", nullable = false)
    private String correlationKey;

    @Column(name = "tenancy_id", nullable = false)
    private String tenancyId;

    @Column(name = "change_type", nullable = false)
    private String changeType;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "detection_count", nullable = false)
    private int detectionCount;

    @Column(name = "trigger_count", nullable = false)
    private int triggerCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "jsonb")
    private String evidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    protected SituationEventEntity() {}

    public SituationEventEntity(String situationId, String correlationKey, String tenancyId,
                                 String changeType, Instant eventTime, Instant firstSeen,
                                 double confidence, int detectionCount, int triggerCount,
                                 String evidence, String metadata) {
        this.situationId = situationId;
        this.correlationKey = correlationKey;
        this.tenancyId = tenancyId;
        this.changeType = changeType;
        this.eventTime = eventTime;
        this.firstSeen = firstSeen;
        this.confidence = confidence;
        this.detectionCount = detectionCount;
        this.triggerCount = triggerCount;
        this.evidence = evidence;
        this.metadata = metadata;
    }

    public UUID getId() { return id; }
    public String getSituationId() { return situationId; }
    public String getCorrelationKey() { return correlationKey; }
    public String getTenancyId() { return tenancyId; }
    public String getChangeType() { return changeType; }
    public Instant getEventTime() { return eventTime; }
    public Instant getFirstSeen() { return firstSeen; }
    public double getConfidence() { return confidence; }
    public int getDetectionCount() { return detectionCount; }
    public int getTriggerCount() { return triggerCount; }
    public String getEvidence() { return evidence; }
    public String getMetadata() { return metadata; }
}
