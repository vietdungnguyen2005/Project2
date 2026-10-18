-- Do not invent a version baseline for historical uploads.
ALTER TABLE migration_job ADD COLUMN import_mode VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNVERIFIED'
    CHECK (import_mode IN ('LEGACY_UNVERIFIED','METADATA_ONLY','VERSIONED_STOCK'));
ALTER TABLE staged_import_row
    ADD COLUMN expected_stock_version BIGINT CHECK (expected_stock_version >= 0),
    ADD COLUMN applied_stock_version BIGINT CHECK (applied_stock_version >= 0),
    ADD COLUMN applied_on_hand INTEGER CHECK (applied_on_hand >= 0),
    ADD COLUMN applied_product_version BIGINT CHECK (applied_product_version >= 0),
    ADD COLUMN stock_movement_id UUID REFERENCES inventory_movement(id),
    ADD CONSTRAINT ck_import_stock_proof CHECK (
        (applied_stock_version IS NULL AND applied_on_hand IS NULL AND stock_movement_id IS NULL)
        OR (applied_stock_version IS NOT NULL AND applied_on_hand IS NOT NULL AND stock_movement_id IS NOT NULL)
    );
