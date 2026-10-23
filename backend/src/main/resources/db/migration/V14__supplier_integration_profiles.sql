CREATE TABLE integration_profile (
    profile_id VARCHAR(48) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    encoding VARCHAR(8) NOT NULL CHECK (encoding IN ('UTF-8','CP932')),
    vendor_code VARCHAR(32) NOT NULL REFERENCES vendor(code),
    mapping JSONB NOT NULL,
    sku_normalization VARCHAR(16) NOT NULL CHECK (sku_normalization IN ('TRIM','TRIM_UPPER')),
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(120) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (profile_id,version)
);

CREATE FUNCTION protect_integration_profile_definition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Profile versions are retained for import provenance';
    END IF;
    IF (NEW.profile_id,NEW.version,NEW.encoding,NEW.vendor_code,NEW.mapping,NEW.sku_normalization,NEW.created_by,NEW.created_at)
       IS DISTINCT FROM
       (OLD.profile_id,OLD.version,OLD.encoding,OLD.vendor_code,OLD.mapping,OLD.sku_normalization,OLD.created_by,OLD.created_at) THEN
        RAISE EXCEPTION 'Publish a new immutable profile version';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_integration_profile BEFORE UPDATE OR DELETE ON integration_profile
    FOR EACH ROW EXECUTE FUNCTION protect_integration_profile_definition();

INSERT INTO integration_profile(profile_id,version,encoding,vendor_code,mapping,sku_normalization,active,created_by,updated_by)
VALUES
('supplier-a',1,'CP932','NAMI','{"sku":"商品コード","name":"商品名","on_hand":"在庫数"}','TRIM_UPPER',TRUE,'migration:synthetic-demo','migration:synthetic-demo'),
('supplier-b',1,'UTF-8','RIVERBYTE','{"sku":"SKU","name":"ProductName","on_hand":"AvailableQuantity"}','TRIM_UPPER',TRUE,'migration:synthetic-demo','migration:synthetic-demo');

ALTER TABLE migration_job ADD COLUMN profile_id VARCHAR(48), ADD COLUMN profile_version INTEGER;
ALTER TABLE migration_job ADD CONSTRAINT migration_profile_reference FOREIGN KEY (profile_id,profile_version)
    REFERENCES integration_profile(profile_id,version);
ALTER TABLE migration_job ADD CONSTRAINT migration_profile_pair CHECK ((profile_id IS NULL) = (profile_version IS NULL));
ALTER TABLE staged_import_row ADD COLUMN expected_product_version BIGINT CHECK (expected_product_version >= 0);
