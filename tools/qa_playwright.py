import os
from pathlib import Path
from playwright.sync_api import expect, sync_playwright

root = Path(__file__).resolve().parents[1]
artifacts = root / "docs" / "evidence"
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
    "orderNumber": "VM-EVIDENCE001", "trackingToken": "evidence-token-0123456789abcdef",
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
    page.route("**/api/catalog/products*", lambda route: route.fulfill(status=200, content_type="application/json", json=catalog))
    page.route("**/api/orders", lambda route: route.fulfill(status=201, json=order))
    page.route("**/api/orders/*?*", lambda route: route.fulfill(json=tracked_order))
    page.route("**/api/ops/imports", lambda route: route.fulfill(json=[]))
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
    operations.screenshot(path=str(artifacts / "operations-ledger.png"), full_page=True)

    tracking = browser.new_page(viewport={"width": 1200, "height": 900})
    install_api_contract(tracking)
    tracking.goto(f"{base_url}/track?order=VM-EVIDENCE001&token=evidence-token-0123456789abcdef", wait_until="networkidle")
    tracking.get_by_role("button", name="Track order").click()
    expect(tracking.get_by_text("Verified ledger entry")).to_be_visible()
    tracking.screenshot(path=str(artifacts / "order-tracking.png"), full_page=True)

    browser.close()

assert not console_errors, f"Browser console errors: {console_errors}"
print(f"evidence={artifacts}")
