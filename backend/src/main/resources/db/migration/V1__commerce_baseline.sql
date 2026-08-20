CREATE TABLE vendor (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE product (
    id UUID PRIMARY KEY,
    vendor_id UUID NOT NULL REFERENCES vendor(id),
    sku VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    category VARCHAR(32) NOT NULL,
    description VARCHAR(800) NOT NULL,
    price_minor INTEGER NOT NULL CHECK (price_minor >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'JPY',
    image_path VARCHAR(255) NOT NULL,
    image_alt VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE inventory_stock (
    product_id UUID PRIMARY KEY REFERENCES product(id),
    on_hand INTEGER NOT NULL CHECK (on_hand >= 0),
    reserved INTEGER NOT NULL DEFAULT 0 CHECK (reserved >= 0 AND reserved <= on_hand),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE inventory_movement (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES product(id),
    movement_type VARCHAR(32) NOT NULL,
    quantity_delta INTEGER NOT NULL,
    reason VARCHAR(200) NOT NULL,
    reference_id VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE customer_order (
    id UUID PRIMARY KEY,
    order_number VARCHAR(40) NOT NULL UNIQUE,
    tracking_token_hash CHAR(64) NOT NULL UNIQUE,
    idempotency_key VARCHAR(120) NOT NULL UNIQUE,
    customer_name VARCHAR(80) NOT NULL,
    customer_email VARCHAR(254) NOT NULL,
    postal_code VARCHAR(12) NOT NULL,
    prefecture VARCHAR(40) NOT NULL,
    city VARCHAR(80) NOT NULL,
    address_line VARCHAR(160) NOT NULL,
    payment_method VARCHAR(20) NOT NULL CHECK (payment_method IN ('INVOICE', 'COD')),
    payment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    fulfillment_status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    subtotal_minor INTEGER NOT NULL,
    shipping_minor INTEGER NOT NULL,
    tax_minor INTEGER NOT NULL,
    grand_total_minor INTEGER NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'JPY',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE order_item (
    order_id UUID NOT NULL REFERENCES customer_order(id),
    product_id UUID NOT NULL REFERENCES product(id),
    sku VARCHAR(64) NOT NULL,
    product_name VARCHAR(160) NOT NULL,
    unit_price_minor INTEGER NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, product_id)
);

CREATE TABLE migration_job (
    id UUID PRIMARY KEY,
    source_name VARCHAR(160) NOT NULL,
    file_checksum CHAR(64) NOT NULL UNIQUE,
    encoding VARCHAR(20) NOT NULL,
    status VARCHAR(24) NOT NULL,
    total_rows INTEGER NOT NULL DEFAULT 0,
    valid_rows INTEGER NOT NULL DEFAULT 0,
    applied_rows INTEGER NOT NULL DEFAULT 0,
    rejected_rows INTEGER NOT NULL DEFAULT 0,
    checkpoint_row INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE staged_import_row (
    job_id UUID NOT NULL REFERENCES migration_job(id) ON DELETE CASCADE,
    row_number INTEGER NOT NULL,
    external_sku VARCHAR(64),
    product_name VARCHAR(160),
    vendor_code VARCHAR(32),
    on_hand INTEGER,
    price_minor INTEGER,
    valid BOOLEAN NOT NULL,
    validation_error VARCHAR(500),
    applied_at TIMESTAMPTZ,
    PRIMARY KEY (job_id, row_number)
);

CREATE TABLE reconciliation_run (
    id UUID PRIMARY KEY,
    migration_job_id UUID NOT NULL REFERENCES migration_job(id),
    mismatch_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE reconciliation_discrepancy (
    id UUID PRIMARY KEY,
    reconciliation_run_id UUID NOT NULL REFERENCES reconciliation_run(id) ON DELETE CASCADE,
    sku VARCHAR(64) NOT NULL,
    discrepancy_type VARCHAR(40) NOT NULL,
    expected_value VARCHAR(160),
    actual_value VARCHAR(160),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolution_note VARCHAR(500),
    resolved_at TIMESTAMPTZ
);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    actor VARCHAR(120) NOT NULL,
    action VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(120) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_product_category ON product(category) WHERE active = TRUE;
CREATE INDEX idx_order_created_at ON customer_order(created_at DESC);
CREATE INDEX idx_migration_job_created_at ON migration_job(created_at DESC);
CREATE INDEX idx_discrepancy_status ON reconciliation_discrepancy(status);

INSERT INTO vendor (id, code, name) VALUES
    ('10000000-0000-0000-0000-000000000001', 'NAMI', 'Nami Studio'),
    ('10000000-0000-0000-0000-000000000002', 'RIVERBYTE', 'Riverbyte'),
    ('10000000-0000-0000-0000-000000000003', 'LUMA', 'Luma Home'),
    ('10000000-0000-0000-0000-000000000004', 'TERRAPACK', 'TerraPack'),
    ('10000000-0000-0000-0000-000000000005', 'MOSS', 'Moss & Grain'),
    ('10000000-0000-0000-0000-000000000006', 'NORTHLINE', 'Northline'),
    ('10000000-0000-0000-0000-000000000007', 'ORBIT', 'Orbit Works'),
    ('10000000-0000-0000-0000-000000000008', 'ATLAS', 'Atlas Goods');

INSERT INTO product (id, vendor_id, sku, name, category, description, price_minor, image_path, image_alt) VALUES
    ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'VM-001', 'AeroKnit travel jacket', 'APPAREL', 'Water-resistant shell, packable hood, and breathable knit lining for city travel.', 11000, '/products/aeroknit-travel-jacket.jpg', 'Model wearing a lightweight black and yellow travel jacket'),
    ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 'VM-002', 'Modular desk organizer', 'OFFICE', 'Stackable trays, cable pass-throughs, and soft-touch dividers for hybrid desks.', 5700, '/products/modular-desk-organizer.jpg', 'Minimal desk setup with organized accessories'),
    ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000003', 'VM-003', 'Ceramic pour-over set', 'HOME', 'Heat-stable ceramic dripper, matching server, and reusable steel filter.', 7800, '/products/ceramic-pour-over-set.jpg', 'Ceramic coffee pour-over kit on a counter'),
    ('20000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000004', 'VM-004', 'Compression packing cubes', 'TRAVEL', 'Ripstop compression cubes that keep outfits organized without extra suitcase bulk.', 4300, '/products/compression-packing-cubes.jpg', 'Travel packing cubes arranged in a suitcase'),
    ('20000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000005', 'VM-005', 'Linen storage baskets', 'HOME', 'Structured linen baskets with washable liners for shelves, laundry, and toys.', 6900, '/products/linen-storage-baskets.jpg', 'Neutral home storage baskets in a bright room'),
    ('20000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000006', 'VM-006', 'Merino commuter tee', 'APPAREL', 'Odor-resistant merino blend tee cut for layering under jackets and overshirts.', 9600, '/products/merino-commuter-tee.jpg', 'Folded premium white t-shirt on a neutral surface'),
    ('20000000-0000-0000-0000-000000000007', '10000000-0000-0000-0000-000000000007', 'VM-007', 'Magnetic cable dock', 'OFFICE', 'Weighted aluminum dock that keeps charging cables locked to the desk edge.', 3600, '/products/magnetic-cable-dock.jpg', 'Laptop desk with organized charging cable accessories'),
    ('20000000-0000-0000-0000-000000000008', '10000000-0000-0000-0000-000000000008', 'VM-008', 'Carry-on toiletry kit', 'TRAVEL', 'Leak-resistant compartments, hook hanger, and TSA-friendly clear pouch.', 5100, '/products/carry-on-toiletry-kit.jpg', 'Travel toiletry kit with grooming items arranged neatly');

INSERT INTO inventory_stock (product_id, on_hand) SELECT id, CASE sku
    WHEN 'VM-001' THEN 32 WHEN 'VM-002' THEN 58 WHEN 'VM-003' THEN 18 WHEN 'VM-004' THEN 44
    WHEN 'VM-005' THEN 27 WHEN 'VM-006' THEN 36 WHEN 'VM-007' THEN 84 ELSE 51 END
FROM product;
