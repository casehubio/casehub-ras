ALTER TABLE ras_situation ADD COLUMN last_triggered TIMESTAMP;
ALTER TABLE ras_situation ADD COLUMN trigger_count INTEGER NOT NULL DEFAULT 0;
