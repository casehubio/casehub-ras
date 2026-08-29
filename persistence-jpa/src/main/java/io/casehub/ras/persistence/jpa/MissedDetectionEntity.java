package io.casehub.ras.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ras_missed_detection")
public class MissedDetectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "situation_id", nullable = false)
    private String situationId;

    @Column(name = "correlation_key", nullable = false)
    private String correlationKey;

    @Column(name = "tenancy_id", nullable = false)
    private String tenancyId;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(name = "reported_by", nullable = false)
    private String reportedBy;

    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected MissedDetectionEntity() {}

    public MissedDetectionEntity(String situationId, String correlationKey, String tenancyId,
                                  Instant eventTime, String reportedBy, UUID reportId, Instant recordedAt) {
        this.situationId = situationId;
        this.correlationKey = correlationKey;
        this.tenancyId = tenancyId;
        this.eventTime = eventTime;
        this.reportedBy = reportedBy;
        this.reportId = reportId;
        this.recordedAt = recordedAt;
    }

    public Long getId() { return id; }
    public String getSituationId() { return situationId; }
    public String getCorrelationKey() { return correlationKey; }
    public String getTenancyId() { return tenancyId; }
    public Instant getEventTime() { return eventTime; }
    public String getReportedBy() { return reportedBy; }
    public UUID getReportId() { return reportId; }
    public Instant getRecordedAt() { return recordedAt; }
}
