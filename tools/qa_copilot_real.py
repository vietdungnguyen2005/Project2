"""Actual local QA through the BFF; ephemeral session is accepted on stdin only."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from playwright.sync_api import expect, sync_playwright

def main():
    config = json.load(sys.stdin)
    out = Path(config["out"])
    out.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 1100})
        context.add_cookies(config["cookies"])
        page = context.new_page()
        page.set_default_timeout(35000)
        page.goto(config["base"]+"/ops")
        page.wait_for_load_state("networkidle")
        panel = page.locator('section[aria-labelledby="copilot-heading"]')
        profile = config["profile"]
        panel.get_by_label("Supplier policy profile").select_option(f'{profile["profile_id"]}:{profile["profile_version"]}')
        panel.get_by_label("Policy question", exact=True).fill("Which original byte encoding does Supplier B supply?")
        panel.get_by_role("button", name="Ask with sources", exact=True).click()
        expect(panel.get_by_text("Suggested policy answer", exact=True)).to_be_visible()
        expect(panel.get_by_label("Policy result")).to_contain_text("UTF-8")
        panel.screenshot(path=str(out/"actual-local-answer-desktop.png"))
        panel.get_by_role("button", name="Source 1", exact=False).click()
        dialog = page.get_by_role("dialog")
        expect(dialog.get_by_text("Supplier B supplies UTF-8", exact=False)).to_be_visible()
        page.screenshot(path=str(out/"actual-current-source-desktop.png"))
        page.keyboard.press("Escape")
        page.set_viewport_size({"width": 390, "height": 844})
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
        panel.screenshot(path=str(out/"actual-local-answer-mobile.png"))
        report = {"scope": "actual authenticated Java/BFF/Python/Ollama UI", "passed": True,
                  "timestamp_utc": datetime.now(timezone.utc).isoformat(), "browser": browser.version,
                  "model": "qwen3.5:4b-q4_K_M", "screenshots": [p.name for p in out.glob("*.png")]}
        print(json.dumps(report))
        browser.close()

if __name__ == "__main__":
    main()
