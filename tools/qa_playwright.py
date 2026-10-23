import os
from pathlib import Path
from playwright.sync_api import expect, sync_playwright

root = Path(__file__).resolve().parents[1]
artifacts = Path(os.environ.get("QA_ARTIFACT_DIR", str(root / "docs" / "evidence")))
artifacts.mkdir(parents=True, exist_ok=True)
base_url = os.environ.get("BASE_URL", "http://127.0.0.1:3000").rstrip("/")

catalog = [
    {
        "sku": "VM-001", "vendorName": "Nami Studio", "name": "AeroKnit travel jacket",
        "category": "APPAREL", "description": "Water-resistant shell for city travel.",
        "priceMinor": 11000, "currency": "JPY", "imagePath": "/products/aeroknit-travel-jacket.jpg",
        "imageAlt": "Model wearing a travel jacket", "availableQuantity": 32,
    },
    {
        "sku": "VM-002", "vendorName": "Riverbyte", "name": "Modular desk organizer",
        "category": "OFFICE", "description": "Stackable trays for hybrid desks.",
        "priceMinor": 5700, "currency": "JPY", "imagePath": "/products/modular-desk-organizer.jpg",
        "imageAlt": "Organized desk", "availableQuantity": 58,
    },
]

order = {
    "orderNumber": "VM-EVIDENCE001", "trackingToken": "f" * 64,
    "paymentStatus": "PENDING", "fulfillmentStatus": "RECEIVED", "subtotalMinor": 11000,
    "shippingMinor": 900, "taxMinor": 880, "grandTotalMinor": 12780, "currency": "JPY",
    "createdAt": "2026-08-09T00:00:00Z",
}

tracked_order = {
    "orderNumber": order["orderNumber"], "paymentStatus": "PENDING", "fulfillmentStatus": "PROCESSING",
    "grandTotalMinor": 12780, "currency": "JPY", "createdAt": order["createdAt"],
    "items": [{"sku": "VM-001", "name": "AeroKnit travel jacket", "unitPriceMinor": 11000, "quantity": 1}],
}

def install_api_contract(page):
    # This suite checks UI contracts with fixtures; backend session behavior has integration tests.
    page.route("**/api/auth/session", lambda route: route.fulfill(json={
        "authenticated": True, "username": "operator@example.test", "roles": ["ROLE_OPERATOR"], "csrfToken": "fixture-csrf"
    }))
    page.route("**/api/catalog/products*", lambda route: route.fulfill(status=200, content_type="application/json", json=catalog))
    page.route("**/api/orders", lambda route: route.fulfill(status=201, json=order))
    page.route("**/api/orders/*?*", lambda route: route.fulfill(json=tracked_order))
    page.route("**/api/ops/imports", lambda route: route.fulfill(json=[]))
    page.route("**/api/ops/integration-profiles", lambda route: route.fulfill(json=[{
        "profileId":"supplier-b","version":1,"encoding":"UTF-8","vendorCode":"RIVERBYTE","active":True,
        "mapping":{"sku":"SKU","name":"ProductName","on_hand":"AvailableQuantity"},"skuNormalization":"TRIM_UPPER"
    }]))
    page.route("**/api/ops/onboarding/supplier-b/1/preview",lambda route: route.fulfill(json={
        "profileId":"supplier-b","version":1,"encoding":"UTF-8",
        "mapping":{"sku":"SKU","name":"ProductName","on_hand":"AvailableQuantity"},
        "acceptedRows":1,"rejectedRows":1,"sample":[{"rowNumber":2,"sku":"VM-002","name":"Modular desk","onHand":60}],
        "errors":[{"rowNumber":3,"reason":"on_hand must be an integer between 0 and 1000000"}],"confirmationReceipt":"fixture-receipt"
    }))
    page.route("**/api/ops/onboarding/supplier-b/1/confirm",lambda route: route.fulfill(status=202,json={
        "id":"fixture-supplier-job","status":"STAGED","profileId":"supplier-b","profileVersion":1,
        "sourceName":"fixture-supplier.csv","encoding":"UTF-8","totalRows":2,"validRows":1,"appliedRows":0,
        "rejectedRows":1,"checkpointRow":0,"createdAt":"2026-10-23T00:00:00Z","importMode":"VERSIONED_STOCK"
    }))
    page.route("**/api/ops/reconciliations", lambda route: route.fulfill(json=[]))
    page.route("**/api/ops/orders", lambda route: route.fulfill(json=[{
        "orderNumber": order["orderNumber"], "paymentStatus": "PENDING", "fulfillmentStatus": "RECEIVED",
        "grandTotalMinor": 12780, "currency": "JPY", "createdAt": order["createdAt"],
        "lines": [{"sku": "VM-001", "productName": "AeroKnit travel jacket", "quantity": 1}],
    }]))

def assert_no_overflow(page):
    dimensions = page.evaluate("() => ({ width: document.documentElement.scrollWidth, viewport: window.innerWidth })")
    assert dimensions["width"] <= dimensions["viewport"], f"Horizontal overflow: {dimensions}"

def load_and_verify_images(page):
    page.evaluate("""async () => {
        const images = Array.from(document.images);
        for (const image of images) {
            image.loading = 'eager';
            image.scrollIntoView({ block: 'center' });
            if (!image.complete) await new Promise(resolve => {
                image.addEventListener('load', resolve, { once: true });
                image.addEventListener('error', resolve, { once: true });
            });
        }
        window.scrollTo(0, 0);
    }""")
    broken = page.evaluate("() => Array.from(document.images).filter(image => !image.naturalWidth).map(image => image.currentSrc)")
    assert not broken, f"Broken images: {broken}"

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True)
    console_errors = []

    desktop = browser.new_page(viewport={"width": 1440, "height": 1000})
    desktop.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
    install_api_contract(desktop)
    desktop.goto(base_url, wait_until="networkidle")
    expect(desktop.get_by_role("heading", name="V-Market")).to_be_visible()
    expect(desktop.get_by_role("heading", name="Mobile-fast catalog")).to_be_visible()
    desktop.get_by_role("button", name="Add").first.click()
    desktop.get_by_label("Full name").fill("Yuki Tanaka")
    desktop.get_by_label("Email").fill("yuki@example.jp")
    desktop.get_by_label("Postal code").fill("100-0001")
    desktop.get_by_label("Prefecture").fill("Tokyo")
    desktop.get_by_label("City").fill("Chiyoda-ku")
    desktop.get_by_label("Address line").fill("Chiyoda 1-1")
    desktop.locator('input[type="checkbox"]').check()
    desktop.get_by_role("button", name="Place order").click()
    expect(desktop.get_by_text("Order VM-EVIDENCE001 confirmed")).to_be_visible()
    assert_no_overflow(desktop)
    load_and_verify_images(desktop)
    desktop.screenshot(path=str(artifacts / "storefront-desktop.png"), full_page=True)

    mobile = browser.new_page(viewport={"width": 390, "height": 844}, is_mobile=True)
    install_api_contract(mobile)
    mobile.goto(base_url, wait_until="networkidle")
    expect(mobile.get_by_role("heading", name="V-Market")).to_be_visible()
    assert_no_overflow(mobile)
    load_and_verify_images(mobile)
    mobile.screenshot(path=str(artifacts / "storefront-mobile.png"), full_page=True)

    operations = browser.new_page(viewport={"width": 1440, "height": 1000})
    install_api_contract(operations)
    operations.goto(f"{base_url}/ops", wait_until="networkidle")
    expect(operations.get_by_role("heading", name="OPERATIONS LEDGER")).to_be_visible()
    expect(operations.get_by_text("VM-EVIDENCE001")).to_be_visible()
    assert_no_overflow(operations)
    supplier=operations.locator('section[aria-labelledby="supplier-heading"]')
    operations.get_by_role("button",name="Preview & validate",exact=True).click()
    expect(supplier.get_by_text("1 accepted · 1 rejected",exact=True)).to_be_visible()
    expect(supplier.get_by_role("button",name="Confirm & queue",exact=True)).to_be_disabled()
    supplier.get_by_role("checkbox").check()
    expect(supplier.get_by_role("button",name="Confirm & queue",exact=True)).to_be_enabled()
    # Editing invalidates the approval instead of silently confirming a different payload.
    operations.get_by_label("Supplier CSV payload (UTF-8 only)",exact=True).fill("changed")
    expect(supplier.get_by_role("button",name="Confirm & queue",exact=True)).not_to_be_visible()
    operations.get_by_role("button",name="Preview & validate",exact=True).click()
    supplier.get_by_role("checkbox").check()
    supplier.get_by_role("button",name="Confirm & queue",exact=True).click()
    expect(supplier.get_by_text("Acknowledged job fixture-supplier-job",exact=False)).to_be_visible()
    operations.set_viewport_size({"width":390,"height":844})
    assert_no_overflow(operations)
    operations.set_viewport_size({"width":1440,"height":1000})
    operations.screenshot(path=str(artifacts / "operations-ledger.png"), full_page=True)

    tracking = browser.new_page(viewport={"width": 1200, "height": 900})
    install_api_contract(tracking)
    tracking.goto(f"{base_url}/track?order=VM-EVIDENCE001&token={'f' * 64}", wait_until="networkidle")
    tracking.get_by_role("button", name="Track order").click()
    expect(tracking.get_by_text("Verified ledger entry")).to_be_visible()
    tracking.screenshot(path=str(artifacts / "order-tracking.png"), full_page=True)

    browser.close()

assert not console_errors, f"Browser console errors: {console_errors}"
print(f"evidence={artifacts}")
