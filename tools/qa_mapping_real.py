"""Real local OPERATOR browser. Credentials arrive on stdin, never command arguments."""
import base64
import json
import sys
from pathlib import Path
from playwright.sync_api import expect, sync_playwright


def main(config):
    output = Path(config["output"])
    output.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1100})
        page.set_default_timeout(10000)
        page.goto(config["base"] + "/ops")
        page.wait_for_load_state("networkidle")
        page.get_by_label("Username", exact=True).fill(config["username"])
        page.get_by_label("Password", exact=True).fill(config["password"])
        page.get_by_role("button", name="Sign in", exact=True).click()
        expect(page.get_by_role("heading", name="OPERATIONS LEDGER")).to_be_visible()
        records = []
        for supplier, encoding, profile_id, text in [
            ("supplier-b", "utf-8", "browser-b", "SKU,ProductName,AvailableQuantity\r\nVM-002,Reviewed desk,60\r\n"),
            ("supplier-a", "cp932", "browser-a", "商品コード,商品名,在庫数\r\nVM-001,確認済み商品,32\r\n"),
        ]:
            raw = text.encode(encoding)
            source = page.get_by_label("Supplier profile version", exact=True)
            source.select_option(supplier + ":1")
            page.get_by_label("Supplier original CSV file", exact=True).set_input_files({"name": profile_id + ".csv", "mimeType": "text/csv", "buffer": raw})
            panel = page.get_by_role("region", name="Mapping draft / human review")
            with page.expect_response(lambda r: r.url.endswith("/api/ops/ai/mapping/suggest")) as suggested:
                panel.get_by_role("button", name="Suggest mapping", exact=True).click()
            assert suggested.value.status == 200, "Real signed mapping unavailable"
            draft = suggested.value.json()["draft"]
            assert draft["vendor_verification"] == "bff_profile_snapshot"
            panel.get_by_label("New profile ID", exact=True).fill(profile_id)
            panel.get_by_label("New profile version", exact=True).fill("1")
            panel.get_by_role("checkbox").check()
            with page.expect_response(lambda r: r.url.endswith("/api/ops/integration-profiles") and r.request.method == "POST") as published:
                panel.get_by_role("button", name="Create inactive profile", exact=True).click()
            assert published.value.status == 201 and published.value.json()["active"] is False
            page.screenshot(path=str(output / (profile_id + "-inactive.png")), full_page=True)
            panel.get_by_role("button", name="Activate saved profile", exact=True).click()
            expect(source).to_have_value(profile_id + ":1")
            section = page.locator('section[aria-labelledby="supplier-heading"]')
            with page.expect_response(lambda r: r.url.endswith("/preview")) as preview:
                section.get_by_role("button", name="Preview & validate", exact=True).click()
            assert preview.value.status == 200 and preview.value.json()["acceptedRows"] == 1
            expect(section.get_by_role("button", name="Confirm & queue", exact=True)).to_be_disabled()
            section.get_by_role("checkbox").last.check()
            if supplier == "supplier-b":
                captured = {}

                def lose_response(route):
                    response = route.fetch()
                    assert response.status == 202
                    captured["job_id"] = response.json()["id"]
                    route.fulfill(status=502, json={"code": "CONTROLLED_LOST_202"})

                page.route("**/confirm", lose_response)
                section.get_by_role("button", name="Confirm & queue", exact=True).click()
                expect(section.get_by_role("button", name="Inspect current Import runs", exact=True)).to_be_visible()
                expect(section.get_by_role("button", name="Preview & validate", exact=True)).to_be_disabled()
                page.unroute("**/confirm", lose_response)
                section.get_by_role("button", name="Inspect current Import runs", exact=True).click()
                expect(section.get_by_text(captured["job_id"], exact=False).last).to_be_visible()
                section.get_by_role("button", name="I inspected the jobs", exact=False).click()
                with page.expect_response(lambda r: r.url.endswith("/preview")) as renewed:
                    section.get_by_role("button", name="Preview & validate", exact=True).click()
                assert renewed.value.status == 200
                expect(section.get_by_role("button", name="Confirm & queue", exact=True)).to_be_visible()
                expect(section.get_by_role("checkbox").last).not_to_be_checked()
                section.get_by_role("checkbox").last.check()
                with page.expect_response(lambda r: r.url.endswith("/confirm")) as retried:
                    section.get_by_role("button", name="Confirm & queue", exact=True).click()
                assert retried.value.status == 202 and retried.value.json()["id"] == captured["job_id"]
                job_id = captured["job_id"]
            else:
                with page.expect_response(lambda r: r.url.endswith("/confirm")) as confirmed:
                    section.get_by_role("button", name="Confirm & queue", exact=True).click()
                assert confirmed.value.status == 202
                job_id = confirmed.value.json()["id"]
            expect(section.get_by_text("Pending: stock application is not yet confirmed.", exact=False)).to_be_visible()
            records.append({"profile_id": profile_id, "job_id": job_id, "encoding": encoding, "raw_base64": base64.b64encode(raw).decode(), "lost_202_recovered": supplier == "supplier-b"})
        page.set_viewport_size({"width": 390, "height": 844})
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
        page.screenshot(path=str(output / "real-mobile.png"), full_page=True)
        browser.close()
    print(json.dumps({"status": "PASS", "records": records}))


if __name__ == "__main__":
    main(json.load(sys.stdin))
