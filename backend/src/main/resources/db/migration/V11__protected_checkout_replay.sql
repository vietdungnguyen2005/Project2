-- No historical token is reconstructed or rotated. Existing tracking hashes/links remain valid.
ALTER TABLE customer_order ADD COLUMN tracking_replay_ciphertext TEXT;
