CREATE TABLE ras_situation_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    situation_id    VARCHAR(255) NOT NULL,
    correlation_key VARCHAR(255) NOT NULL,
    tenancy_id      VARCHAR(255) NOT NULL,
    change_type     VARCHAR(50) NOT NULL,
    event_time      TIMESTAMP WITH TIME ZONE NOT NULL,
    first_seen      TIMESTAMP WITH TIME ZONE NOT NULL,
    confidence      DOUBLE PRECISION NOT NULL,
    detection_count INT NOT NULL,
    trigger_count   INT NOT NULL,
    evidence        JSONB,
    metadata        JSONB
);

CREATE INDEX idx_situation_event_tenant_sit_time
    ON ras_situation_event (tenancy_id, situation_id, event_time);

CREATE INDEX idx_situation_event_tenant_time
    ON ras_situation_event (tenancy_id, event_time);

CREATE INDEX idx_situation_event_correlation
    ON ras_situation_event (tenancy_id, correlation_key, event_time);
