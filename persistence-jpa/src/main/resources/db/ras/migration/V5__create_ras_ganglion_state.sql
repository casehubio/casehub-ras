CREATE TABLE ras_ganglion_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ganglion_id VARCHAR(255) NOT NULL,
    situation_id VARCHAR(255) NOT NULL,
    correlation_key VARCHAR(255) NOT NULL,
    tenancy_id VARCHAR(255) NOT NULL,
    state JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_ganglion_state UNIQUE (ganglion_id, situation_id, correlation_key, tenancy_id)
);

CREATE INDEX idx_ganglion_state_situation_id ON ras_ganglion_state (situation_id);
