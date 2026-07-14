package io.casehub.ras.persistence.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

@Entity
@Table(name = "ras_ganglion_state",
       uniqueConstraints = @UniqueConstraint(
           columnNames = {"ganglion_id", "situation_id", "correlation_key", "tenancy_id"}))
public class GanglionStateEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "ganglion_id", nullable = false)
    private String ganglionId;

    @Column(name = "situation_id", nullable = false)
    private String situationId;

    @Column(name = "correlation_key", nullable = false)
    private String correlationKey;

    @Column(name = "tenancy_id", nullable = false)
    private String tenancyId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state", columnDefinition = "jsonb", nullable = false)
    private String state;

    @Version
    private Long version = 0L;

    protected GanglionStateEntity() {}

    public GanglionStateEntity(String ganglionId, String situationId,
                               String correlationKey, String tenancyId, String state) {
        this.ganglionId = ganglionId;
        this.situationId = situationId;
        this.correlationKey = correlationKey;
        this.tenancyId = tenancyId;
        this.state = state;
    }

    public UUID getId() { return id; }
    public String getGanglionId() { return ganglionId; }
    public String getSituationId() { return situationId; }
    public String getCorrelationKey() { return correlationKey; }
    public String getTenancyId() { return tenancyId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Long getVersion() { return version; }
}
