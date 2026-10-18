CREATE TABLE import_work_item (
    job_id UUID PRIMARY KEY REFERENCES migration_job(id) ON DELETE CASCADE,
    state VARCHAR(16) NOT NULL DEFAULT 'READY'
        CHECK (state IN ('READY','CLAIMED','FINISHED','FAILED','BLOCKED')),
    generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_import_work_claim CHECK (
        (state='CLAIMED' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state<>'CLAIMED' AND claim_token IS NULL AND lease_until IS NULL)
    )
);

-- Never infer ownership or automatically resume historical active executions.
INSERT INTO import_work_item(job_id,state)
SELECT id,CASE WHEN status IN ('COMPLETED','COMPLETED_WITH_ERRORS') THEN 'FINISHED'
               WHEN status='FAILED' THEN 'FAILED' ELSE 'BLOCKED' END
FROM migration_job;

CREATE INDEX idx_import_work_ready ON import_work_item(created_at,job_id) WHERE state='READY';
CREATE INDEX idx_import_work_expiring ON import_work_item(lease_until,job_id) WHERE state='CLAIMED';
