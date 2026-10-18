CREATE INDEX idx_checkout_replay_cleanup ON customer_order(created_at, id)
    WHERE tracking_replay_ciphertext IS NOT NULL;
