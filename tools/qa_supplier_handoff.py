"""Headless UI fault tests with mocked HTTP, not Java/DB acceptance evidence."""
import json
import hashlib
import os
import time
from pathlib import Path

from playwright.sync_api import expect, sync_playwright

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("BASE_URL", "http://127.0.0.1:3000")
OUT = Path(os.environ.get("QA_ARTIFACT_DIR", ROOT / ".local" / "p10-browser"))
OUT.mkdir(parents=True, exist_ok=True)


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1080})
        state = {"expiry": int(time.time()) - 1, "previews": 0, "confirms": 0, "imports": 0, "inspection_fails": False, "confirm_ok": False, "byte_checks": []}
        preview_bytes = {}
        mapping = {"sku": "SKU", "name": "ProductName", "on_hand": "AvailableQuantity"}

        def api(route):
            path = route.request.url.split("/api/", 1)[1]
            if path == "auth/session":
                data = {"authenticated": True, "username": "synthetic", "roles": ["ROLE_OPERATOR"], "csrfToken": "fixture"}
            elif path == "ops/integration-profiles":
                data = [{"profileId": name, "version": 1, "encoding": enc, "vendorCode": vendor, "mapping": columns, "skuNormalization": "TRIM", "active": True}
                        for name, enc, vendor, columns in [("supplier-b", "UTF-8", "RIVERBYTE", mapping), ("supplier-a", "CP932", "NAMI", {"sku": "商品コード", "name": "商品名", "on_hand": "在庫数"})]]
            elif path.endswith("/preview"):
                state["previews"] += 1
                name = path.split("/")[2]
                preview_bytes[name] = route.request.post_data_buffer
                data = {"profileId": name, "version": 1, "encoding": "CP932" if name == "supplier-a" else "UTF-8", "mapping": mapping,
                        "acceptedRows": 1, "rejectedRows": 0, "sample": [], "errors": [],
                        "confirmationReceipt": f'{state["expiry"]}:' + ":".join(["a" * 64] * 3)}
            elif path.endswith("/confirm"):
                state["confirms"] += 1
                name = path.split("/")[2]
                assert route.request.post_data_buffer == preview_bytes[name]
                state["byte_checks"].append({"profile": name, "sha256": hashlib.sha256(preview_bytes[name]).hexdigest()})
                if state["confirm_ok"]:
                    route.fulfill(status=202, json={"id": f"mock-job-{state['confirms']}", "status": "STAGED", "profileId": name, "profileVersion": 1,
                        "sourceName": "synthetic.csv", "encoding": "CP932" if name == "supplier-a" else "UTF-8", "totalRows": 1, "validRows": 1,
                        "appliedRows": 0, "rejectedRows": 0, "checkpointRow": 0, "createdAt": "2026-11-01T00:00:00Z"})
                    return
                route.fulfill(status=502, json={"message": "Controlled lost response"})
                return
            elif path == "ops/imports":
                state["imports"] += 1
                if state["inspection_fails"]:
                    route.fulfill(status=401, json={"message": "Controlled expired session"})
                    return
                data = []
            else:
                data = []
            route.fulfill(json=data)

        page.route("**/api/**", api)
        page.goto(BASE + "/ops")
        page.wait_for_load_state("networkidle")
        panel = page.locator('section[aria-labelledby="supplier-heading"]')
        panel.get_by_role("button", name="Preview & validate", exact=True).click()
        expect(panel.get_by_text("Preview expired", exact=False)).to_be_visible()
        expect(panel.get_by_role("button", name="Confirm & queue", exact=True)).to_be_disabled()
        assert state["confirms"] == 0
        page.screenshot(path=str(OUT / "expired.png"), full_page=True)
        state["expiry"] = int(time.time()) + 600
        panel.get_by_role("button", name="Preview & validate", exact=True).click()
        panel.get_by_role("checkbox").check()
        panel.get_by_role("button", name="Confirm & queue", exact=True).click()
        expect(panel.get_by_role("button", name="Inspect current Import runs", exact=True)).to_be_visible()
        expect(panel.get_by_role("button", name="Confirm & queue", exact=True)).to_be_disabled()
        expect(panel.get_by_role("button", name="Preview & validate", exact=True)).to_be_disabled()
        expect(panel.get_by_label("Supplier source filename")).to_be_disabled()
        assert state["confirms"] == 1, state
        state["inspection_fails"] = True
        panel.get_by_role("button", name="Inspect current Import runs", exact=True).click()
        expect(panel.get_by_text("Inspection failed.", exact=False)).to_be_visible()
        expect(panel.get_by_role("button", name="Preview & validate", exact=True)).to_be_disabled()
        assert state["confirms"] == 1
        state["inspection_fails"] = False
        panel.get_by_role("button", name="Inspect current Import runs", exact=True).click()
        expect(panel.get_by_text("An absent job does not prove", exact=False)).to_be_visible()
        expect(panel.get_by_role("button", name="Preview & validate", exact=True)).to_be_disabled()
        page.screenshot(path=str(OUT / "inspection.png"), full_page=True)
        panel.get_by_role("button", name="I inspected the jobs", exact=False).click()
        expect(panel.get_by_role("button", name="Preview & validate", exact=True)).to_be_enabled()
        expect(panel.get_by_role("button", name="Confirm & queue", exact=True)).to_have_count(0)
        assert state["previews"] == 2 and state["confirms"] == 1
        panel.get_by_role("button", name="Preview & validate", exact=True).click()
        expect(panel.get_by_role("checkbox")).not_to_be_checked()
        expect(panel.get_by_role("button", name="Confirm & queue", exact=True)).to_be_disabled()
        assert state["confirms"] == 1
        state["confirm_ok"] = True
        panel.get_by_role("checkbox").check()
        panel.get_by_role("button", name="Confirm & queue", exact=True).click()
        expect(panel.get_by_text("Pending: stock application is not yet confirmed.", exact=False)).to_be_visible()
        assert state["confirms"] == 2
        panel.get_by_label("Supplier profile version").select_option("supplier-a:1")
        raw = "商品コード,商品名,在庫数\r\nVM-001,机,32\r\n".encode("cp932")
        panel.get_by_label("Supplier original CSV file").set_input_files({"name": "synthetic-cp932.csv", "mimeType": "text/csv", "buffer": raw})
        panel.get_by_role("button", name="Preview & validate", exact=True).click()
        expect(panel.get_by_role("checkbox")).not_to_be_checked()
        panel.get_by_role("checkbox").check()
        panel.get_by_role("button", name="Confirm & queue", exact=True).click()
        expect(panel.get_by_text("Acknowledged job mock-job-3", exact=False)).to_be_visible()
        assert preview_bytes["supplier-a"] == raw
        assert state["confirms"] == 3
        # Expiry also locks a preview already on screen; rendering never extends it.
        page.clock.install()
        state["expiry"] = int(time.time()) + 3
        panel.get_by_role("button", name="Preview & validate", exact=True).click()
        panel.get_by_role("checkbox").check()
        expect(panel.get_by_role("button", name="Confirm & queue", exact=True)).to_be_enabled()
        page.clock.fast_forward(5000)
        expect(panel.get_by_text("Preview expired", exact=False)).to_be_visible()
        expect(panel.get_by_role("button", name="Confirm & queue", exact=True)).to_be_disabled()
        assert state["confirms"] == 3
        page.set_viewport_size({"width": 390, "height": 844})
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
        page.screenshot(path=str(OUT / "mobile-expired.png"), full_page=True)
        browser.close()
        (OUT / "report.json").write_text(json.dumps({"scope": "mocked browser only", "state": state, "passed": True}, indent=2))


if __name__ == "__main__":
    main()
