ALTER TABLE reconciliation_run
    ADD COLUMN changed_since_import_rows INTEGER NOT NULL DEFAULT 0 CHECK (changed_since_import_rows >= 0),
    ADD COLUMN stock_not_verified_rows INTEGER NOT NULL DEFAULT 0 CHECK (stock_not_verified_rows >= 0);
-- Historical reports did not have a stock/version baseline. Preserve their findings but expose that scope.
UPDATE reconciliation_run run SET stock_not_verified_rows=(
    SELECT COUNT(*) FROM staged_import_row row
    WHERE row.job_id=run.migration_job_id AND row.valid=TRUE AND row.applied_at IS NOT NULL
      AND row.applied_stock_version IS NULL
);
