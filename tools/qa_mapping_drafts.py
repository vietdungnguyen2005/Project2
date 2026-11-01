"""Browser-only draft review/explicit publish/activation acceptance; HTTP is mocked."""
import base64
import hashlib
import json
import os
import time
from pathlib import Path
from playwright.sync_api import expect, sync_playwright

OUT = Path(os.environ.get("QA_ARTIFACT_DIR", ".local/p10b-browser"))
OUT.mkdir(parents=True, exist_ok=True)
BASE = os.environ.get("BASE_URL", "http://127.0.0.1:3000")


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1100})
        page.set_default_timeout(5000)
        columns = {"sku": "SKU", "name": "ProductName", "on_hand": "AvailableQuantity"}
        profiles = [{"profileId": "supplier-b", "version": 1, "encoding": "UTF-8", "vendorCode": "RIVERBYTE", "mapping": columns, "skuNormalization": "TRIM", "active": True}]
        state = {"draft": 0, "publish": 0, "activate": 0, "preview": 0, "confirm": 0}
        faults = {"draft_unavailable": False, "publish_lost": False}
        original = None

        def api(route):
            nonlocal original
            request = route.request
            path = request.url.split("/api/", 1)[1]
            if path == "auth/session":
                data = {"authenticated": True, "username": "synthetic", "roles": ["ROLE_OPERATOR"], "csrfToken": "fixture"}
            elif path == "ops/ai/mapping/suggest":
                state["draft"] += 1
                if faults["draft_unavailable"]:
                    route.fulfill(status=503, json={"code": "CONTROLLED_UNAVAILABLE"})
                    return
                body = request.post_data_json
                assert "selected_vendor_code" not in body and "profile_candidates" not in body
                original = base64.b64decode(body["raw_base64"])
                # Deliberately wrong but structurally valid hint; human corrects both columns.
                wrong = {"sku": "ProductName", "name": "SKU", "on_hand": "AvailableQuantity"}
                data = {"draft": {"request_id": "11111111-1111-4111-8111-111111111111", "raw_sha256": hashlib.sha256(original).hexdigest(), "declared_encoding": "UTF-8", "selected_vendor_code": "RIVERBYTE", "vendor_code": "RIVERBYTE", "vendor_verification": "bff_profile_snapshot", "status": "needs_review", "assignments": [{"source_header": v, "target_field": k, "uncertainty": "high", "basis": "model", "reason": "Controlled wrong hint", "citations": []} for k, v in wrong.items()], "unmapped_headers": [], "missing_required_fields": [], "warnings": ["BUSINESS_VALIDATION_REQUIRED"], "reason": "HUMAN_REVIEW_REQUIRED", "profile_differences": [{"profile_id": "supplier-b", "version": 1, "active": True, "encoding_matches": True, "changes": {}}], "requires_human_acceptance": True, "provider_mode": "fake"}, "evidence": [], "source_profile": {"profile_id": "supplier-b", "version": 1, "encoding": "UTF-8", "vendor_code": "RIVERBYTE", "mapping": columns, "sku_normalization": "TRIM", "active": True, "required_fields": ["sku", "name", "on_hand"]}}
            elif path == "ops/integration-profiles":
                if request.method == "POST":
                    state["publish"] += 1
                    data = {**request.post_data_json, "active": False}
                    assert data["mapping"] == columns
                    profiles.append(data)
                    if faults["publish_lost"]:
                        route.fulfill(status=502, json={"code": "CONTROLLED_LOST_CREATE"})
                        return
                else:
                    data = profiles
            elif path.startswith("ops/integration-profiles/"):
                state["activate"] += 1
                assert request.method == "PATCH" and request.post_data_json == {"active": True}
                profiles[-1]["active"] = True
                data = profiles[-1]
            elif path.endswith("/preview"):
                state["preview"] += 1
                assert request.post_data_buffer == original
                data = {"profileId": profiles[-1]["profileId"], "version": 1, "encoding": "UTF-8", "mapping": columns, "acceptedRows": 1, "rejectedRows": 0, "sample": [], "errors": [], "confirmationReceipt": f"{int(time.time())+600}:" + ":".join(["a" * 64] * 3)}
            elif path.endswith("/confirm"):
                state["confirm"] += 1
                assert request.post_data_buffer == original
                data = {"id": "mock-human-import", "status": "STAGED", "sourceName": "synthetic.csv", "encoding": "UTF-8", "totalRows": 1, "validRows": 1, "appliedRows": 0, "rejectedRows": 0, "checkpointRow": 0, "createdAt": "2026-11-01T00:00:00Z"}
            else:
                data = []
            route.fulfill(json=data)

        page.route("**/api/**", api)
        page.goto(BASE + "/ops")
        page.wait_for_load_state("networkidle")
        panel = page.get_by_role("region", name="Mapping draft / human review")
        panel.get_by_role("button", name="Suggest mapping", exact=True).click()
        expect(panel.get_by_text("Controlled wrong hint", exact=False).first).to_be_visible()
        assert state == {"draft": 1, "publish": 0, "activate": 0, "preview": 0, "confirm": 0}
        panel.get_by_label("Source column for sku", exact=True).fill("SKU")
        panel.get_by_label("Source column for name", exact=True).fill("ProductName")
        panel.get_by_label("New profile ID", exact=True).fill("human-reviewed")
        panel.get_by_label("New profile version", exact=True).fill("1")
        expect(panel.get_by_role("button", name="Create inactive profile", exact=True)).to_be_disabled()
        panel.get_by_role("checkbox").check()
        panel.get_by_role("button", name="Create inactive profile", exact=True).click()
        expect(panel.get_by_role("button", name="Activate saved profile", exact=True)).to_be_visible()
        assert state["publish"] == 1 and state["activate"] == 0 and state["preview"] == 0
        page.screenshot(path=str(OUT / "inactive-profile.png"), full_page=True)
        panel.get_by_role("button", name="Activate saved profile", exact=True).click()
        expect(page.get_by_label("Supplier profile version")).to_have_value("human-reviewed:1")
        assert state["activate"] == 1 and state["preview"] == 0 and state["confirm"] == 0
        supplier = page.locator('section[aria-labelledby="supplier-heading"]')
        supplier.get_by_role("button", name="Preview & validate", exact=True).click()
        expect(supplier.get_by_role("button", name="Confirm & queue", exact=True)).to_be_disabled()
        supplier.get_by_role("checkbox").last.check()
        supplier.get_by_role("button", name="Confirm & queue", exact=True).click()
        expect(supplier.get_by_text("Acknowledged job mock-human-import", exact=False)).to_be_visible()
        assert state == {"draft": 1, "publish": 1, "activate": 1, "preview": 1, "confirm": 1}
        faults["draft_unavailable"] = True
        panel.get_by_role("button", name="Suggest mapping", exact=True).click()
        expect(panel.get_by_text("Draft unavailable.", exact=False)).to_be_visible()
        expect(panel.get_by_label("Source column for sku", exact=True)).to_be_enabled()
        assert state["publish"] == 1
        panel.get_by_label("New profile ID", exact=True).fill("manual-fallback")
        panel.get_by_role("checkbox").check()
        faults["publish_lost"] = True
        panel.get_by_role("button", name="Create inactive profile", exact=True).click()
        expect(panel.get_by_role("button", name="Inspect saved profiles", exact=True)).to_be_visible()
        expect(panel.get_by_role("button", name="Create inactive profile", exact=True)).to_be_disabled()
        assert state["publish"] == 2
        panel.get_by_role("button", name="Inspect saved profiles", exact=True).click()
        expect(panel.get_by_text("Matching version found: inactive.", exact=True)).to_be_visible()
        panel.get_by_role("button", name="I inspected the profile outcome", exact=True).click()
        expect(panel.get_by_text("Saved manual-fallback", exact=False)).to_be_visible()
        assert state == {"draft": 2, "publish": 2, "activate": 1, "preview": 1, "confirm": 1}
        page.set_viewport_size({"width": 390, "height": 844})
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
        page.screenshot(path=str(OUT / "manual-fallback-mobile.png"), full_page=True)
        browser.close()
        (OUT / "report.json").write_text(json.dumps({"scope": "mocked HTTP browser only", "passed": True, "counts": state}, indent=2))


if __name__ == "__main__":
    main()
