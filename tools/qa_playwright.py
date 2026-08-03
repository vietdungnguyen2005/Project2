import os
from pathlib import Path
from playwright.sync_api import expect, sync_playwright

root = Path(__file__).resolve().parents[1]
artifacts = root / "qa-artifacts"
artifacts.mkdir(exist_ok=True)
base_url = os.environ.get("BASE_URL", "http://127.0.0.1:3000").rstrip("/")

def assert_all_images_loaded(page):
    page.evaluate(
        """async () => {
            const imgs = Array.from(document.images);
            for (const img of imgs) {
                img.loading = "eager";
                img.scrollIntoView({ block: "center", inline: "nearest" });
                await new Promise((resolve) => setTimeout(resolve, 180));
            }
            window.scrollTo(0, 0);
        }"""
    )
    images = page.evaluate(
        """async () => {
            const imgs = Array.from(document.images);
            await Promise.all(imgs.map((img) => {
                if (img.complete) return Promise.resolve();
                return new Promise((resolve) => {
                    const timeout = setTimeout(resolve, 15000);
                    const finish = () => {
                        clearTimeout(timeout);
                        resolve();
                    };
                    img.addEventListener("load", finish, { once: true });
                    img.addEventListener("error", finish, { once: true });
                });
            }));
            return imgs.map((img) => ({
                src: img.currentSrc || img.src,
                width: img.naturalWidth,
                height: img.naturalHeight,
            }));
        }"""
    )
    broken = [image for image in images if image["width"] == 0 or image["height"] == 0]
    assert not broken, f"Broken catalog images: {broken}"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)

    desktop = browser.new_page(viewport={"width": 1440, "height": 1000})
    desktop.set_default_timeout(120_000)
    desktop.set_default_navigation_timeout(120_000)
    desktop.goto(base_url, wait_until="domcontentloaded")
    desktop.evaluate("window.localStorage.clear()")
    desktop.goto(base_url, wait_until="domcontentloaded")
    expect(desktop.get_by_role("heading", name="V-Market")).to_be_visible(timeout=120_000)
    expect(desktop.get_by_role("heading", name="Mobile-fast catalog")).to_be_visible(timeout=120_000)
    hero_image = desktop.locator("img").first
    srcset = hero_image.get_attribute("srcset")
    assert srcset and "w," in srcset
    expect(hero_image).to_have_attribute("sizes", "(max-width: 1024px) 92vw, 54vw")
    priority_preload = desktop.locator('link[rel="preload"][as="image"]').first
    expect(priority_preload).to_have_attribute("imagesizes", "(max-width: 1024px) 92vw, 54vw")
    assert_all_images_loaded(desktop)
    desktop.screenshot(path=str(artifacts / "desktop.png"), full_page=True)

    desktop.get_by_role("button", name="Add").first.click()
    increase = desktop.get_by_role("button", name="Increase AeroKnit travel jacket quantity")
    for _ in range(5):
        increase.click()
    expect(desktop.get_by_label("6 items in cart").first).to_be_visible(timeout=10_000)
    desktop.get_by_label("Search catalog").fill("desk")
    expect(desktop.get_by_role("heading", name="Modular desk organizer")).to_be_visible()
    desktop.get_by_label("Search catalog").fill("")
    desktop.get_by_label("Full name").fill("V Market Buyer")
    desktop.get_by_label("Email").fill("buyer@example.com")
    desktop.get_by_label("Address").fill("1 Market Street")
    desktop.get_by_label("City").fill("Bangkok")
    desktop.get_by_label("Payment").select_option("cod")
    desktop.locator('input[type="checkbox"]').check()
    desktop.get_by_role("button", name="Place order").click()
    expect(desktop.get_by_text("confirmed")).to_be_visible(timeout=10_000)
    expect(desktop.get_by_text("Payment pending: Pay on delivery.")).to_be_visible(timeout=10_000)

    mobile = browser.new_page(viewport={"width": 390, "height": 844}, is_mobile=True)
    mobile.set_default_timeout(120_000)
    mobile.set_default_navigation_timeout(120_000)
    mobile.goto(base_url, wait_until="domcontentloaded")
    mobile.evaluate("window.localStorage.clear()")
    mobile.goto(base_url, wait_until="domcontentloaded")
    expect(mobile.get_by_role("heading", name="V-Market")).to_be_visible(timeout=120_000)
    expect(mobile.get_by_role("heading", name="Mobile-fast catalog")).to_be_visible(timeout=120_000)
    assert_all_images_loaded(mobile)
    mobile.screenshot(path=str(artifacts / "mobile.png"), full_page=True)

    compact = browser.new_page(viewport={"width": 320, "height": 740}, is_mobile=True)
    compact.set_default_timeout(120_000)
    compact.set_default_navigation_timeout(120_000)
    compact.goto(base_url, wait_until="domcontentloaded")
    compact.evaluate("window.localStorage.clear()")
    compact.goto(base_url, wait_until="domcontentloaded")
    expect(compact.get_by_role("heading", name="V-Market")).to_be_visible(timeout=120_000)
    expect(compact.get_by_role("button", name="Add").first).to_be_visible(timeout=120_000)
    assert_all_images_loaded(compact)
    compact.screenshot(path=str(artifacts / "compact-mobile.png"), full_page=True)

    browser.close()

print(f"desktop={artifacts / 'desktop.png'}")
print(f"mobile={artifacts / 'mobile.png'}")
print(f"compact={artifacts / 'compact-mobile.png'}")
print(f"base_url={base_url}")
