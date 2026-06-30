package io.casehub.ras.persistence.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ras_situation",
       uniqueConstraints = @UniqueConstraint(
           columnNames = {"situation_id", "correlation_key", "tenancy_id"}))
public class SituationEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "situation_id", nullable = false)
    private String situationId;

    @Column(name = "correlation_key", nullable = false)
    private String correlationKey;

    @Column(name = "tenancy_id", nullable = false)
    private String tenancyId;

    @Column(name = "first_signal", nullable = false)
    private Instant firstSignal;

    @Column(name = "last_signal", nullable = false)
    private Instant lastSignal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detections", columnDefinition = "jsonb", nullable = false)
    private String detections;

    @Version
    private Long version = 0L;

    @Column(name = "policy_triggered", nullable = false)
    private boolean policyTriggered = false;

    @Column(name = "last_triggered")
    private Instant lastTriggered;

    @Column(name = "trigger_count", nullable = false)
    private int triggerCount = 0;

    protected SituationEntity() {}

    public SituationEntity(String situationId, String correlationKey, String tenancyId,
                           Instant firstSignal, Instant lastSignal, String detections) {
        this.situationId = situationId;
        this.correlationKey = correlationKey;
        this.tenancyId = tenancyId;
        this.firstSignal = firstSignal;
        this.lastSignal = lastSignal;
        this.detections = detections;
    }

    public UUID getId() { return id; }
    public String getSituationId() { return situationId; }
    public String getCorrelationKey() { return correlationKey; }
    public String getTenancyId() { return tenancyId; }
    public Instant getFirstSignal() { return firstSignal; }
    public void setFirstSignal(Instant firstSignal) { this.firstSignal = firstSignal; }
    public Instant getLastSignal() { return lastSignal; }
    public void setLastSignal(Instant lastSignal) { this.lastSignal = lastSignal; }
    public String getDetections() { return detections; }
    public void setDetections(String detections) { this.detections = detections; }
    public Long getVersion() { return version; }
    public boolean isPolicyTriggered() { return policyTriggered; }
    public Instant getLastTriggered() { return lastTriggered; }
    public void setLastTriggered(Instant lastTriggered) { this.lastTriggered = lastTriggered; }
    public int getTriggerCount() { return triggerCount; }
    public void setTriggerCount(int triggerCount) { this.triggerCount = triggerCount; }
}
