-- Historical orders keep their original key/token; no unverified guest owner is backfilled.
ALTER TABLE customer_order ADD COLUMN checkout_scope_hash CHAR(64)
    CHECK (checkout_scope_hash IS NULL OR checkout_scope_hash ~ '^[0-9a-f]{64}$');
