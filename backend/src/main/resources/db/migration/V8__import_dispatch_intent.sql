-- Only explicitly asynchronous requests enter the dispatcher. Existing synchronous work is never stolen.
ALTER TABLE import_work_item ADD COLUMN dispatch_requested BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_import_work_dispatch_ready ON import_work_item(created_at,job_id)
    WHERE state='READY' AND dispatch_requested=TRUE;
