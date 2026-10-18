-- Existing orders keep a null fingerprint: their request cannot be reconstructed safely.
-- Reusing a legacy key is rejected rather than replaying potentially unrelated data.
ALTER TABLE customer_order ADD COLUMN request_hash CHAR(64);
