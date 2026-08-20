ALTER TABLE customer_order ADD COLUMN privacy_accepted_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
