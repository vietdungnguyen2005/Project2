"""P12 headless browser interaction checks with explicitly mocked HTTP responses."""
import asyncio
import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

from playwright.async_api import async_playwright, expect

BASE = os.environ.get("BASE_URL", "http://127.0.0.1:13014")
OUT = Path(os.environ.get("QA_ARTIFACT_DIR", ".local/p12-browser"))


async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        page = await browser.new_page(viewport={"width": 1440, "height": 1100})
        page.set_default_timeout(8000)
        page.on("pageerror", lambda error: print("BROWSER_ERROR", error))
        chunk, run_id, conversation = (str(uuid4()) for _ in range(3))
        trace = str(uuid4())
        citation = {"document_id": chunk, "version_id": chunk, "chunk_id": chunk, "locator": {"kind": "section", "value": "encoding"}}
        profiles = [{"profileId": "supplier-b", "version": 1, "encoding": "UTF-8", "vendorCode": "RIVERBYTE", "active": True, "mapping": {"sku": "SKU", "name": "ProductName", "on_hand": "AvailableQuantity"}, "skuNormalization": "TRIM"}]
        faults = {"qa": "answer", "source": False, "auth": "operator", "run": "normal", "username": "synthetic"}
        counts = {"start": 0, "resume": 0, "inspect": 0}
        now = int(time.time())
        run = {"run_id": run_id, "conversation_id": conversation, "initial_request_id": trace, "status": "started", "phase": "idle", "revision": 0, "step": 0, "created_at": now, "updated_at": now, "expires_at": now+600, "reason": None, "observations": [], "outcome": None, "can_resume": True, "can_cancel": True}

        async def api(route):
            nonlocal run
            path = route.request.url.split("/api/", 1)[1]
            if path == "auth/session":
                if faults["auth"] == "down":
                    await route.fulfill(status=503, json={"code": "DEPENDENCY_UNAVAILABLE"})
                    return
                data = {"authenticated": faults["auth"] != "expired", "username": faults["username"], "roles": ["ROLE_OPERATOR"] if faults["auth"] == "operator" else [], "csrfToken": "fixture"}
            elif path == "ops/integration-profiles":
                data = profiles
            elif path == "ops/ai/ask":
                assert route.request.post_data_json["profile_id"] == "supplier-b"
                assert "collection_ids" not in route.request.post_data_json
                if faults["qa"] in ("503", "504", "403"):
                    code = {"503": "DEPENDENCY_UNAVAILABLE", "504": "DEADLINE_EXCEEDED", "403": "ACCESS_DENIED"}[faults["qa"]]
                    await route.fulfill(status=int(faults["qa"]), json={"code": code}, headers={"X-Request-ID": trace})
                    return
                abstain = faults["qa"] == "abstain"
                data = {"request_id": trace, "status": "abstained" if abstain else "answered", "answer": None if abstain else "Original bytes use UTF-8.", "reason": "NO_SUPPORTED_EVIDENCE" if abstain else None, "citations": [] if abstain else [citation], "confidence": "unknown" if abstain else "low", "provider_mode": "fake"}
            elif path == "ops/ai/citations/read":
                if faults["source"]:
                    await route.fulfill(status=404, json={"code": "DOCUMENT_NOT_AVAILABLE"}, headers={"X-Request-ID": trace})
                    return
                data = {"request_id": trace, "citation": citation, "excerpt": "Original bytes use UTF-8. <img src=x onerror=alert(1)>", "truncated": False}
            elif path == "ops/ai/runs":
                action = route.request.post_data_json["action"]
                counts[action] = counts.get(action, 0)+1
                observations = []
                if action == "start":
                    run = {**run, "revision": 0, "status": "started", "phase": "idle", "step": 0, "observations": [], "outcome": None, "can_resume": True, "can_cancel": True}
                elif action == "resume":
                    if faults["run"] == "hold":
                        run = {**run, "revision": 2, "status": "waiting_for_operator", "reason": "OUTCOME_UNKNOWN"}
                        await asyncio.sleep(2)
                        try:
                            await route.fulfill(status=504, json={"code": "DEADLINE_EXCEEDED"})
                        except Exception:
                            pass  # The browser intentionally aborted this request.
                        return
                    handoff = faults["run"] == "handoff"
                    observed = {"call_id": str(uuid4()), "tool_name": "get_imports", "status": "ok", "observed_at": datetime.now(timezone.utc).isoformat(), "truncated": False, "error_code": None, "data": {"imports": []}}
                    observations = [] if handoff else [observed]
                    summary = {k: v for k, v in observed.items() if k != "data"}
                    run = {**run, "status": "completed", "phase": "terminal", "revision": 6, "step": 0 if handoff else 1, "reason": None, "observations": [] if handoff else [{**summary, "item_count": 0}], "outcome": {"kind": "handoff" if handoff else "final", "status": "human_action_required" if handoff else "answered", "handoff_reason": "IMPORT_CONFIRM" if handoff else None}, "can_resume": False, "can_cancel": False}
                elif action == "cancel":
                    run = {**run, "status": "cancelled", "phase": "terminal", "revision": run["revision"]+1, "can_resume": False, "can_cancel": False, "reason": "OPERATOR_CANCELLED"}
                data = {"run": run, "observations": observations}
            else:
                data = []
            await route.fulfill(json=data, headers={"X-Request-ID": trace})

        await page.route("**/api/**", api)
        await page.goto(BASE+"/ops")
        await page.wait_for_load_state("networkidle")
        panel = page.locator('section[aria-labelledby="copilot-heading"]')
        await expect(panel.get_by_role("heading", name="Operator Copilot", exact=True)).to_be_visible()
        await expect(panel.get_by_label("Supplier policy profile")).to_have_value("supplier-b:1")
        await panel.get_by_label("Policy question", exact=True).fill("Which encoding preserves the original bytes?")
        await panel.get_by_role("button", name="Ask with sources", exact=True).click()
        await expect(panel.get_by_text("Suggested policy answer", exact=True)).to_be_visible()
        source_button = panel.get_by_role("button", name="Source 1", exact=False)
        await source_button.focus()
        await page.keyboard.press("Enter")
        dialog = page.get_by_role("dialog")
        await expect(dialog.get_by_text("Original bytes use UTF-8. <img src=x onerror=alert(1)>", exact=True)).to_be_visible()
        assert await dialog.locator("img").count() == 0
        await page.keyboard.press("Escape")
        await expect(source_button).to_be_focused()
        faults["source"] = True
        await source_button.click()
        await expect(dialog.get_by_text("This source is no longer available with current access.", exact=True)).to_be_visible()
        await dialog.get_by_role("button", name="Close source").click()
        faults["qa"] = "abstain"
        await panel.get_by_role("button", name="Ask with sources", exact=True).click()
        await expect(panel.get_by_text("No supported answer", exact=True)).to_be_visible()
        for fault in ("503", "504", "403"):
            faults["qa"] = fault
            await panel.get_by_role("button", name="Ask with sources", exact=True).click()
            await expect(panel.get_by_role("alert")).to_be_visible()
            await expect(panel.get_by_text("Suggested policy answer", exact=True)).to_have_count(0)
        faults["qa"] = "answer"
        await panel.get_by_role("button", name="Ask with sources", exact=True).click()
        await expect(panel.get_by_text("Suggested policy answer", exact=True)).to_be_visible()
        await panel.get_by_label("Investigation question", exact=True).fill("List imports")
        await panel.get_by_role("button", name="Start investigation", exact=True).click()
        await expect(panel.get_by_text("Investigation finished", exact=True)).to_be_visible()
        await expect(panel.get_by_text("No import jobs returned by the authorized read.", exact=True)).to_be_visible()
        await panel.screenshot(path=str(OUT/"copilot-panel-desktop.png"))
        await panel.get_by_role("button", name="Inspect saved run", exact=True).click()
        await expect(panel.get_by_text("Historical summary only.", exact=False)).to_be_visible()
        assert counts == {"start": 1, "resume": 1, "inspect": 1}
        await page.screenshot(path=str(OUT/"copilot-desktop.png"), full_page=True)
        await panel.get_by_role("button", name="Ask another investigation question", exact=True).click()
        await expect(panel.get_by_label("Investigation conversation")).to_contain_text("List imports")
        await panel.get_by_label("Investigation question", exact=True).fill("List imports again")
        faults["run"] = "hold"
        await panel.get_by_role("button", name="Start investigation", exact=True).click()
        await expect(panel.get_by_text("Reading authorized tools…", exact=True)).to_be_visible()
        await panel.get_by_role("button", name="Stop waiting", exact=True).click()
        await expect(panel.get_by_text("The request stopped before", exact=False)).to_be_visible()
        await panel.get_by_role("button", name="Inspect saved run", exact=True).click()
        await expect(panel.get_by_role("button", name="Resume read-only investigation", exact=True)).to_be_visible()
        await panel.get_by_role("button", name="Cancel investigation", exact=True).click()
        await expect(panel.get_by_text("Investigation cancelled", exact=True)).to_be_visible()
        await panel.get_by_role("button", name="Forget reference", exact=True).click()
        faults["run"] = "handoff"
        await panel.get_by_label("Investigation question", exact=True).fill("Confirm this import")
        await panel.get_by_role("button", name="Start investigation", exact=True).click()
        await expect(panel.get_by_text("Human action required", exact=True)).to_be_visible()
        await panel.get_by_role("link", name="Review the current profile", exact=False).click()
        assert page.url.endswith("#supplier-heading")
        await page.set_viewport_size({"width": 390, "height": 844})
        assert await page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
        await panel.scroll_into_view_if_needed()
        await page.screenshot(path=str(OUT/"copilot-mobile.png"), full_page=True)
        await panel.screenshot(path=str(OUT/"copilot-panel-mobile.png"))
        assert "question" not in (await page.evaluate("JSON.stringify(sessionStorage)"))
        faults["username"] = "another-synthetic-operator"
        await page.evaluate("window.dispatchEvent(new Event('vmarket-session-check'))")
        await expect(page.get_by_text("Signed in as another-synthetic-operator", exact=True)).to_be_visible()
        await expect(panel.get_by_text("Human action required", exact=True)).to_have_count(0)
        faults["auth"] = "expired"
        await page.evaluate("window.dispatchEvent(new Event('vmarket-session-check'))")
        await expect(page.get_by_role("heading", name="Operator sign-in")).to_be_visible()
        await expect(panel).to_have_count(0)
        faults["auth"] = "unauthorized"
        await page.reload()
        await expect(page.get_by_text("Your account does not have operator access.", exact=True)).to_be_visible()
        faults["auth"] = "down"
        await page.reload()
        await expect(page.get_by_text("Unable to check your session.", exact=False)).to_be_visible()
        await expect(panel).to_have_count(0)
        (OUT/"report.json").write_text(json.dumps({"scope": "mocked HTTP browser interaction, not model quality", "passed": True, "timestamp_utc": datetime.now(timezone.utc).isoformat(), "browser": browser.version, "viewports": ["1440x1100", "390x844"], "counts": counts, "checks": ["source keyboard and Escape focus restore", "escaped source HTML", "revoked source", "no-answer", "model503/504", "access403", "read results versus historical summaries", "no automatic retry", "stop/inspect/cancel", "human handoff", "mobile overflow", "identity switch clears local results", "expired/unauthorized/Java down"]}, indent=2))
        await browser.close()


if __name__ == "__main__":
    asyncio.run(main())
