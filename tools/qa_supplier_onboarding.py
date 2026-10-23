"""Real browser/BFF/Java supplier acceptance; only the runner-owned disposable DB is inspected."""
import json
import os
import subprocess
import time
from pathlib import Path
from playwright.sync_api import expect, sync_playwright

root = Path(__file__).resolve().parents[1]
artifacts = root / "docs" / "fde" / "evidence"
artifacts.mkdir(parents=True, exist_ok=True)
base = os.environ.get("BASE_URL", "http://127.0.0.1:13002")


def sql(statement):
    container = os.environ["TEST_POSTGRES_CONTAINER"]
    assert container.startswith("vmarket-auth-pg-"), "Only the runner-owned disposable DB is allowed"
    return subprocess.run(["docker", "exec", container, "psql", "-U", "vmarket", "-d", "vmarket", "-v", "ON_ERROR_STOP=1", "-At", "-c", statement],
                          check=True, capture_output=True, text=True, timeout=20).stdout.strip()


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True)
    context = browser.new_context(viewport={"width": 1440, "height": 1000}, accept_downloads=True)
    anonymous = browser.new_context()
    assert anonymous.request.get(f"{base}/api/ops/integration-profiles").status == 401
    assert anonymous.request.post(f"{base}/api/ops/onboarding/supplier-b/1/preview", data="x", headers={"Content-Type": "text/csv"}).status == 401
    anonymous.close()
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(f"{base}/ops", wait_until="networkidle")
    page.get_by_label("Username").fill(os.environ["TEST_OPERATOR_USERNAME"])
    page.get_by_label("Password", exact=True).fill(os.environ["TEST_OPERATOR_PASSWORD"])
    page.get_by_role("button", name="Sign in", exact=True).click()
    expect(page.get_by_role("heading", name="Supplier onboarding", exact=True)).to_be_visible(timeout=20000)
    section = page.locator('section[aria-labelledby="supplier-heading"]')
    initial = sql("SELECT (SELECT COUNT(*) FROM migration_job)||':'||(SELECT COUNT(*) FROM customer_order)||':'||(SELECT COUNT(*) FROM inventory_movement)||':'||(SELECT SUM(version) FROM product)||':'||(SELECT SUM(version) FROM inventory_stock)")
    page.get_by_role("button", name="Preview & validate", exact=True).click()
    expect(section.get_by_text("1 accepted · 1 rejected", exact=True)).to_be_visible()
    expect(section.get_by_role("button", name="Confirm & queue", exact=True)).to_be_disabled()
    after = sql("SELECT (SELECT COUNT(*) FROM migration_job)||':'||(SELECT COUNT(*) FROM customer_order)||':'||(SELECT COUNT(*) FROM inventory_movement)||':'||(SELECT SUM(version) FROM product)||':'||(SELECT SUM(version) FROM inventory_stock)")
    assert initial == after, "Dry-run changed business/import state"
    with page.expect_download() as download:
        section.get_by_role("button", name="Export sanitized report", exact=True).click()
    report_path = root / "backend" / "target" / "supplier-validation-report.json"
    download.value.save_as(report_path)
    report = json.loads(report_path.read_text(encoding="utf-8"))
    assert set(report) == {"profileId", "version", "encoding", "acceptedRows", "rejectedRows", "errors"}
    assert "Modular desk" not in report_path.read_text(encoding="utf-8")
    section.screenshot(path=str(artifacts / "supplier-preview-desktop.png"))
    page.set_viewport_size({"width": 390, "height": 844})
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth"), "Supplier UI overflows on mobile"
    section.scroll_into_view_if_needed()
    page.screenshot(path=str(artifacts / "supplier-preview-mobile.png"))
    page.set_viewport_size({"width": 1440, "height": 1000})
    # Switching profile removes approval; CP932 uses original bytes, not browser re-encoding.
    page.get_by_label("Supplier profile version", exact=True).select_option("supplier-a:1")
    expect(section.get_by_role("button", name="Confirm & queue", exact=True)).not_to_be_visible()
    cp932 = "商品コード,商品名,在庫数\nVM-001,軽量ジャケット,35\n".encode("cp932")
    page.get_by_label("Supplier original CSV file", exact=True).set_input_files({"name": "supplier-a-cp932.csv", "mimeType": "text/csv", "buffer": cp932})
    page.get_by_role("button", name="Preview & validate", exact=True).click()
    expect(section.get_by_text("1 accepted · 0 rejected", exact=True)).to_be_visible()
    expect(section.get_by_text("Row 2 · VM-001 · 軽量ジャケット · stock 35", exact=True)).to_be_visible()
    section.screenshot(path=str(artifacts / "supplier-cp932-preview.png"))
    # Return to supplier B. Editing invalidates the previous receipt; preview before explicit confirmation.
    page.get_by_label("Supplier profile version", exact=True).select_option("supplier-b:1")
    page.get_by_label("Supplier original CSV file", exact=True).set_input_files([])
    csv = "SKU,ProductName,AvailableQuantity\nVM-002,Modular desk,60\nVM-005,Linen baskets,invalid\n"
    page.get_by_label("Supplier CSV payload (UTF-8 only)", exact=True).fill(csv)
    page.get_by_label("Supplier source filename", exact=True).fill("supplier-acceptance.csv")
    with page.expect_response(lambda response: response.url.endswith("/supplier-b/1/preview")) as preview:
        page.get_by_role("button", name="Preview & validate", exact=True).click()
    assert preview.value.status == 200
    receipt = preview.value.json()["confirmationReceipt"]
    section.get_by_role("checkbox").check()
    with page.expect_response(lambda response: response.url.endswith("/supplier-b/1/confirm")) as confirmed:
        section.get_by_role("button", name="Confirm & queue", exact=True).click()
    assert confirmed.value.status == 202, confirmed.value.text()
    job = confirmed.value.json()
    assert job["profileId"] == "supplier-b" and job["profileVersion"] == 1
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        jobs = context.request.get(f"{base}/api/ops/imports").json()
        completed = next(item for item in jobs if item["id"] == job["id"])
        if completed["status"] == "COMPLETED_WITH_ERRORS":
            break
        time.sleep(0.2)
    assert completed["appliedRows"] == 1 and completed["rejectedRows"] == 1, completed
    article = page.locator("article").filter(has=page.get_by_role("heading", name="supplier-acceptance.csv", exact=True))
    expect(article.get_by_text("COMPLETED_WITH_ERRORS", exact=True)).to_be_visible(timeout=15000)
    article.get_by_role("button", name="Inspect run", exact=True).click()
    expect(article.get_by_text("Work FINISHED", exact=False)).to_be_visible()
    expect(article.get_by_role("button", name="Restart failed import", exact=True)).not_to_be_visible()
    with page.expect_response(lambda response: response.url.endswith("/api/ops/reconciliations") and response.request.method == "POST") as reconciled:
        article.get_by_role("button", name="Reconcile", exact=True).click()
    assert reconciled.value.status == 201, reconciled.value.text()
    consistency = reconciled.value.json()
    assert consistency["mismatchCount"] == 0 and consistency["stockNotVerifiedRows"] == 0 and consistency["changedSinceImportRows"] == 0
    page.locator('section[aria-labelledby="jobs-heading"]').screenshot(path=str(artifacts / "supplier-import-inspection.png"))
    page.locator('section[aria-labelledby="reconciliation-heading"]').screenshot(path=str(artifacts / "supplier-reconciliation.png"))
    csrf = context.request.get(f"{base}/api/auth/session").json()["csrfToken"]
    headers = {"Content-Type": "text/csv", "X-CSRF-TOKEN": csrf, "X-Source-Name": "duplicate.csv", "X-Preview-Receipt": receipt}
    duplicate = context.request.post(f"{base}/api/ops/onboarding/supplier-b/1/confirm", headers=headers, data=csv)
    assert duplicate.status == 202 and duplicate.json()["id"] == job["id"], duplicate.text()
    assert sql(f"SELECT COUNT(*) FROM inventory_movement WHERE reference_id='{job['id']}'") == "1"
    assert context.request.post(f"{base}/api/ops/onboarding/supplier-b/1/preview", data=csv, headers={"Content-Type": "text/csv"}).status == 403
    assert not errors, errors
    summary = {"synthetic": True, "realBackend": True, "dryRunUnchanged": initial == after,
               "acceptedRows": 1, "rejectedRows": 1, "duplicateJobs": 1, "movementsAfterDuplicate": 1,
               "mismatches": 0, "stockNotVerifiedRows": 0, "cp932Decoded": True,
               "authorizationAndCsrf": True, "mobileOverflow": False, "reportExcludedPayloadAndReceipt": True}
    (root / "backend" / "target" / "supplier-browser-results.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    browser.close()
print("PASS: real supplier preview (UTF-8/CP932), dry-run, report, approval, async import, inspection, dedup, reconciliation, mobile and auth/CSRF")
