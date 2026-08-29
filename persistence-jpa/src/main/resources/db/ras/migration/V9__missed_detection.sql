CREATE TABLE ras_missed_detection (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    situation_id    VARCHAR(255) NOT NULL,
    correlation_key VARCHAR(255) NOT NULL,
    tenancy_id      VARCHAR(255) NOT NULL,
    event_time      TIMESTAMP WITH TIME ZONE NOT NULL,
    reported_by     VARCHAR(255) NOT NULL,
    report_id       UUID NOT NULL,
    recorded_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(situation_id, correlation_key, tenancy_id, event_time),
    UNIQUE(report_id)
);

CREATE INDEX idx_missed_detection_situation ON ras_missed_detection(situation_id, tenancy_id, event_time);
