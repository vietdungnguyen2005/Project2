TRUNCATE TABLE
    reconciliation_discrepancy,
    reconciliation_run,
    staged_import_row,
    migration_job,
    inventory_movement,
    order_item,
    customer_order,
    audit_event
CASCADE;

UPDATE product SET
    name = CASE sku
        WHEN 'VM-001' THEN 'AeroKnit travel jacket'
        WHEN 'VM-002' THEN 'Modular desk organizer'
        WHEN 'VM-003' THEN 'Ceramic pour-over set'
        WHEN 'VM-004' THEN 'Compression packing cubes'
        WHEN 'VM-005' THEN 'Linen storage baskets'
        WHEN 'VM-006' THEN 'Merino commuter tee'
        WHEN 'VM-007' THEN 'Magnetic cable dock'
        ELSE 'Carry-on toiletry kit' END,
    price_minor = CASE sku
        WHEN 'VM-001' THEN 11000 WHEN 'VM-002' THEN 5700 WHEN 'VM-003' THEN 7800
        WHEN 'VM-004' THEN 4300 WHEN 'VM-005' THEN 6900 WHEN 'VM-006' THEN 9600
        WHEN 'VM-007' THEN 3600 ELSE 5100 END,
    version = 0;

UPDATE inventory_stock s SET
    on_hand = CASE p.sku
        WHEN 'VM-001' THEN 32 WHEN 'VM-002' THEN 58 WHEN 'VM-003' THEN 18
        WHEN 'VM-004' THEN 44 WHEN 'VM-005' THEN 27 WHEN 'VM-006' THEN 36
        WHEN 'VM-007' THEN 84 ELSE 51 END,
    reserved = 0,
    version = 0
FROM product p WHERE p.id = s.product_id;
