package io.casehub.ras.persistence.jpa;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ras_outcome_record")
public class OutcomeRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "situation_id", nullable = false)
    private String situationId;

    @Column(name = "correlation_key", nullable = false)
    private String correlationKey;

    @Column(name = "tenancy_id", nullable = false)
    private String tenancyId;

    @Column(name = "outcome_label", nullable = false)
    private String outcomeLabel;

    @Column(name = "classification", nullable = false)
    @Enumerated(EnumType.STRING)
    private io.casehub.ras.api.OutcomeClassification classification;

    @Column(name = "closed_at", nullable = false)
    private Instant closedAt;

    @Column(name = "case_id", nullable = false, unique = true)
    private UUID caseId;

    protected OutcomeRecordEntity() {}

    public OutcomeRecordEntity(String situationId, String correlationKey, String tenancyId,
                                String outcomeLabel, io.casehub.ras.api.OutcomeClassification classification,
                                Instant closedAt, UUID caseId) {
        this.situationId = situationId;
        this.correlationKey = correlationKey;
        this.tenancyId = tenancyId;
        this.outcomeLabel = outcomeLabel;
        this.classification = classification;
        this.closedAt = closedAt;
        this.caseId = caseId;
    }

    public Long getId() { return id; }
    public String getSituationId() { return situationId; }
    public String getCorrelationKey() { return correlationKey; }
    public String getTenancyId() { return tenancyId; }
    public String getOutcomeLabel() { return outcomeLabel; }
    public io.casehub.ras.api.OutcomeClassification getClassification() { return classification; }
    public Instant getClosedAt() { return closedAt; }
    public UUID getCaseId() { return caseId; }
}
