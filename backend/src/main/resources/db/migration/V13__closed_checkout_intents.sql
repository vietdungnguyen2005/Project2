-- Guest-scoped key hashes only; no raw capability, customer data or response token.
CREATE TABLE closed_checkout_intent (
    key_hash CHAR(64) PRIMARY KEY CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    closed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
