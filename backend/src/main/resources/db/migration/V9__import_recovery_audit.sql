ALTER TABLE import_work_item
    ADD COLUMN recovery_count INTEGER NOT NULL DEFAULT 0 CHECK (recovery_count >= 0),
    ADD COLUMN last_recovered_at TIMESTAMPTZ,
    ADD COLUMN last_recovery_reason VARCHAR(80);
