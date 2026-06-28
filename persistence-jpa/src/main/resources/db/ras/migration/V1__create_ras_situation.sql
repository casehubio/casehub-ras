CREATE TABLE ras_situation (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    situation_id VARCHAR(255) NOT NULL,
    correlation_key VARCHAR(255) NOT NULL,
    tenancy_id VARCHAR(255) NOT NULL,
    first_signal TIMESTAMP WITH TIME ZONE NOT NULL,
    last_signal TIMESTAMP WITH TIME ZONE NOT NULL,
    detections JSONB NOT NULL DEFAULT '[]',
    CONSTRAINT uk_ras_situation UNIQUE (situation_id, correlation_key, tenancy_id)
);

CREATE INDEX idx_ras_situation_last_signal ON ras_situation (last_signal);
