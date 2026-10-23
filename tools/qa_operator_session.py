"""Exercise the real browser -> BFF -> Spring session flow against disposable QA services."""
import os
import csv
import io
import uuid
import time
import subprocess
from pathlib import Path
from playwright.sync_api import expect, sync_playwright

base = os.environ.get("BASE_URL", "http://127.0.0.1:13002")
artifacts = Path(__file__).resolve().parents[1] / "backend" / "target" / "operator-browser"
artifacts.mkdir(parents=True, exist_ok=True)

def wait_for_job(context, job_id):
    deadline=time.monotonic()+25
    while time.monotonic()<deadline:
        response=context.request.get(f"{base}/api/ops/imports")
        assert response.status==200,response.text()
        job=next((item for item in response.json() if item["id"]==job_id),None)
        if job and job["status"] not in ("STAGED","RUNNING"):
            return job
        time.sleep(0.2)
    raise AssertionError(f"Queued import {job_id} did not reach a terminal state")

def qa_sql(sql):
    container=os.environ["TEST_POSTGRES_CONTAINER"]
    assert container.startswith("vmarket-auth-pg-"),"Fault injection must target the runner-owned disposable DB"
    return subprocess.run(["docker","exec",container,"psql","-U","vmarket","-d","vmarket","-v","ON_ERROR_STOP=1","-c",sql],
        check=True,capture_output=True,text=True).stdout

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True)
    context = browser.new_context(viewport={"width": 1280, "height": 900})
    page = context.new_page()
    page_errors=[]
    page.on("pageerror",lambda error:page_errors.append(str(error)))
    page.goto(f"{base}/ops", wait_until="networkidle")
    expect(page.get_by_role("heading", name="Operator sign-in")).to_be_visible()
    page.screenshot(path=str(artifacts / "sign-in.png"), full_page=True)
    page.get_by_label("Username").fill(os.environ["TEST_OPERATOR_USERNAME"])
    page.get_by_label("Password", exact=True).fill(os.environ["TEST_OPERATOR_PASSWORD"])
    with page.expect_response(lambda response: response.url.endswith("/api/auth/login")) as login_response:
        page.get_by_role("button", name="Sign in", exact=True).click()
    assert login_response.value.status == 204, f"Login HTTP {login_response.value.status}: {login_response.value.text()}"
    expect(page.get_by_role("heading", name="OPERATIONS LEDGER")).to_be_visible(timeout=20000)
    session_cookie = next(cookie for cookie in context.cookies() if cookie["name"] == "VMARKET_SESSION")
    assert session_cookie["httpOnly"] and session_cookie["sameSite"] == "Lax"
    csrf = context.request.get(f"{base}/api/auth/session").json()["csrfToken"]
    before_invalid = context.request.get(f"{base}/api/ops/imports").json()
    csv_headers = {"Content-Type": "text/csv", "X-Source-Name": "invalid-contract.csv",
        "X-Source-Encoding": "UTF-8", "X-CSRF-TOKEN": csrf, "Prefer": "respond-async"}
    for invalid in (
        b"sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,broken-\xff,32,11000\n",
        b"sku,vendor_code,name,on_hand,price_minor,on_hand\nVM-001,NAMI,duplicate,32,11000,999\n",
        b"sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,extra,32,11000,unexpected\n",
    ):
        rejected = context.request.post(f"{base}/api/ops/imports", headers=csv_headers, data=invalid)
        assert rejected.status == 400 and rejected.json()["code"] == "INVALID_IMPORT", rejected.text()
    oversized = b"x" * 5_000_001
    rejected = context.request.post(f"{base}/api/ops/imports", headers=csv_headers, data=oversized)
    assert rejected.status == 413 and rejected.json()["code"] == "REQUEST_TOO_LARGE", rejected.text()
    # Test the Java boundary independently, not only the BFF's size check. Secrets stay in memory.
    direct_headers = dict(csv_headers, **{"X-V-Market-BFF-Secret": os.environ["BFF_SHARED_SECRET"],
        "X-V-Market-Ops-Secret": os.environ["VMARKET_OPS_SECRET"]})
    rejected = context.request.post(f"{os.environ['BACKEND_ORIGIN']}/api/ops/imports", headers=direct_headers, data=oversized)
    assert rejected.status == 413 and rejected.json()["code"] == "REQUEST_TOO_LARGE", rejected.text()
    after_invalid = context.request.get(f"{base}/api/ops/imports").json()
    assert {job["id"] for job in before_invalid} == {job["id"] for job in after_invalid}, "Invalid input created import work"
    print("PASS: real BFF/Java invalid encoding, duplicate headers, row shape and independent 413 body boundaries")
    # Real CSV export through the BFF, not a fixture or client-generated version.
    with page.expect_response(lambda response: response.url.endswith("/api/ops/inventory/snapshot")) as exported:
        page.get_by_role("button", name="Load current stock snapshot", exact=True).click()
    assert exported.value.status == 200
    snapshot = exported.value.text()
    page.screenshot(path=str(artifacts / "loaded-snapshot.png"), full_page=True)
    expect(page.get_by_label("CSV payload", exact=True)).to_have_value(snapshot.replace("\r\n", "\n"))
    rows = list(csv.reader(io.StringIO(snapshot)))
    original = next(row for row in rows[1:] if row[1] == "VM-001")
    assert original[-1] == "0", "Disposable seed must begin at version 0"
    def encode_snapshot(row):
        output = io.StringIO()
        writer = csv.writer(output)
        writer.writerows([rows[0], row])
        return output.getvalue()

    adjusted = list(original)
    adjusted[4] = "40"
    page.get_by_label("CSV payload", exact=True).fill(encode_snapshot(adjusted))
    with page.expect_response(lambda response: response.url.endswith("/api/ops/imports") and response.request.method == "POST") as imported:
        page.get_by_role("button", name="Stage & queue", exact=True).click()
    assert imported.value.status == 202, imported.value.text()
    assert imported.value.headers.get("preference-applied")=="respond-async"
    job = imported.value.json()
    job=wait_for_job(context,job["id"])
    assert job["appliedRows"] == 1 and job["importMode"] == "VERSIONED_STOCK"
    import_article=page.locator("article").filter(has=page.get_by_role("heading",name="inventory-snapshot.csv",exact=True))
    expect(import_article.get_by_text("COMPLETED",exact=True)).to_be_visible(timeout=15000)
    warm_catalog = context.request.get(f"{base}/api/catalog/products")
    assert warm_catalog.status == 200
    assert next(product for product in warm_catalog.json() if product["sku"] == "VM-001")["availableQuantity"] == 40
    guest_capability = os.urandom(32).hex()
    sale_key = f"qa-stock-{uuid.uuid4()}"
    sale_payload = {
        "customer": {"name": "QA Buyer", "email": "buyer@example.test", "postalCode": "100-0001", "prefecture": "Tokyo", "city": "Chiyoda", "addressLine": "1-1", "paymentMethod": "COD", "privacyAccepted": True},
        "lines": [{"sku": "VM-001", "quantity": 1}],
    }
    sale = context.request.post(f"{base}/api/orders", headers={"Idempotency-Key": sale_key, "X-Checkout-Session": guest_capability}, data=sale_payload)
    assert sale.status == 201, sale.text()
    replay = context.request.post(f"{base}/api/orders", headers={"Idempotency-Key": sale_key, "X-Checkout-Session": guest_capability}, data=sale_payload)
    assert replay.status == 201 and replay.json() == sale.json(), "Lost-response retry changed the order or tracking capability"
    assert context.request.post(f"{base}/api/orders", headers={"Idempotency-Key": sale_key}, data=sale_payload).status == 400
    assert qa_sql(f"SELECT COUNT(*) FROM customer_order WHERE order_number='{sale.json()['orderNumber']}';").splitlines()[2].strip() == "1"
    print("PASS: real guest-scoped checkout retry retained one order and the original random tracking capability")
    current_catalog = context.request.get(f"{base}/api/catalog/products")
    assert current_catalog.status == 200
    assert next(product for product in current_catalog.json() if product["sku"] == "VM-001")["availableQuantity"] == 39
    print("PASS: warm catalog cache reflects committed checkout stock through the real BFF")
    with page.expect_response(lambda response: response.url.endswith("/api/ops/reconciliations") and response.request.method == "POST") as reconciled:
        import_article.get_by_role("button", name="Reconcile", exact=True).click()
    assert reconciled.value.status == 201, reconciled.value.text()
    report = reconciled.value.json()
    assert report["mismatchCount"] == 0 and report["changedSinceImportRows"] == 1 and report["stockNotVerifiedRows"] == 1
    expect(page.get_by_text("1 rows changed since import", exact=False)).to_be_visible()
    page.screenshot(path=str(artifacts / "version-aware-reconciliation.png"), full_page=True)
    page.set_viewport_size({"width": 390, "height": 844})
    expect(page.get_by_role("heading", name="Reconciliation reports")).to_be_visible()
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "Mobile operations console overflows"
    page.screenshot(path=str(artifacts / "operations-mobile.png"), full_page=True)
    page.set_viewport_size({"width": 1280, "height": 900})
    page.get_by_label("CSV payload", exact=True).fill(encode_snapshot(original))
    with page.expect_response(lambda response: response.url.endswith("/api/ops/imports") and response.request.method == "POST") as stale:
        page.get_by_role("button", name="Stage & queue", exact=True).click()
    assert stale.value.status == 202, stale.value.text()
    stale_job=wait_for_job(context,stale.value.json()["id"])
    assert stale_job["appliedRows"] == 0 and stale_job["rejectedRows"] == 1
    current = context.request.get(f"{base}/api/ops/inventory/snapshot")
    assert current.status == 200
    latest = next(row for row in csv.reader(io.StringIO(current.text())) if len(row) > 1 and row[1] == "VM-001")
    assert latest[4] == "39" and latest[-1] == "2", latest
    restart_url = f"{base}/api/ops/imports/{job['id']}/restart"
    assert context.request.post(restart_url).status == 403
    csrf = context.request.get(f"{base}/api/auth/session").json()["csrfToken"]
    completed = context.request.post(restart_url, headers={"X-CSRF-TOKEN": csrf})
    assert completed.status == 200, completed.text()
    assert completed.json()["id"] == job["id"] and completed.json()["appliedRows"] == 1
    assert completed.json()["status"] == "COMPLETED", "Completed restart must not reapply rows"
    qa_sql("""CREATE FUNCTION qa_fail_import() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN IF NEW.movement_type='LEGACY_IMPORT' AND NEW.product_id='20000000-0000-0000-0000-000000000007'::uuid
      THEN RAISE EXCEPTION 'QA controlled import failure'; END IF; RETURN NEW; END; $$;
      CREATE TRIGGER qa_fail_import BEFORE INSERT ON inventory_movement FOR EACH ROW EXECUTE FUNCTION qa_fail_import();""")
    try:
        page.get_by_label("Source filename",exact=True).fill("controlled-restart.csv")
        page.get_by_label("CSV payload",exact=True).fill("schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version\n2,VM-007,ORBIT,Recoverable cable dock,90,3600,0\n")
        with page.expect_response(lambda response: response.url.endswith("/api/ops/imports") and response.request.method=="POST") as failing:
            page.get_by_role("button",name="Stage & queue",exact=True).click()
        assert failing.value.status==202,failing.value.text()
        failed=wait_for_job(context,failing.value.json()["id"])
        assert failed["status"]=="FAILED" and failed["appliedRows"]==0,failed
        failed_article=page.locator("article").filter(has=page.get_by_role("heading",name="controlled-restart.csv",exact=True))
        failed_article.get_by_role("button",name="Inspect run",exact=True).click()
        expect(page.get_by_role("button",name="Restart failed import",exact=True)).to_be_visible(timeout=15000)
        page.screenshot(path=str(artifacts/"failed-import-restart.png"),full_page=True)
        page.set_viewport_size({"width":390,"height":844})
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"),"Failed import controls overflow on mobile"
        page.screenshot(path=str(artifacts/"failed-import-mobile.png"),full_page=True)
    finally:
        qa_sql("DROP TRIGGER IF EXISTS qa_fail_import ON inventory_movement; DROP FUNCTION IF EXISTS qa_fail_import();")
    with page.expect_response(lambda response: response.url.endswith(f"/api/ops/imports/{failed['id']}/restart")) as restarted:
        page.get_by_role("button",name="Restart failed import",exact=True).click()
    assert restarted.value.status==202,restarted.value.text()
    resumed=wait_for_job(context,failed["id"])
    assert resumed["id"]==failed["id"] and resumed["status"]=="COMPLETED" and resumed["appliedRows"]==1,resumed
    expect(page.get_by_role("button",name="Restart failed import",exact=True)).not_to_be_visible(timeout=15000)
    assert qa_sql(f"SELECT COUNT(*) FROM inventory_movement WHERE reference_id='{failed['id']}' AND movement_type='LEGACY_IMPORT';").splitlines()[2].strip()=="1"
    response = context.request.post(f"{base}/api/ops/reconciliations", data="{}", headers={"Content-Type": "application/json"})
    assert response.status == 403, "An operator mutation without CSRF must fail"
    page.get_by_role("button", name="Sign out", exact=True).click()
    expect(page.get_by_role("heading", name="Operator sign-in")).to_be_visible()
    assert context.request.get(f"{base}/api/ops/imports").status == 401
    assert context.request.post(restart_url).status == 401
    page.goto(base, wait_until="networkidle")
    expect(page.get_by_role("heading", name="Operator sign-in")).not_to_be_visible()
    page.get_by_role("button", name="Add", exact=True).first.click()
    page.get_by_label("Full name", exact=True).fill("QA Guest Buyer")
    page.get_by_label("Email", exact=True).fill("guest@example.test")
    page.get_by_label("Postal code", exact=True).fill("100-0001")
    page.get_by_label("Prefecture", exact=True).fill("Tokyo")
    page.get_by_label("City", exact=True).fill("Chiyoda-ku")
    page.get_by_label("Address line", exact=True).fill("Chiyoda 1-1")
    page.locator('input[type="checkbox"]').check()
    with page.expect_response(lambda response: response.url.endswith("/api/orders") and response.request.method == "POST") as placed:
        page.get_by_role("button", name="Place order", exact=True).click()
    assert placed.value.status == 201, placed.value.text()
    guest_order = placed.value.json()
    expect(page.get_by_text(f"Order {guest_order['orderNumber']} confirmed", exact=True)).to_be_visible()
    # Inspect equality in memory only: never persist the guest capability or tracking token in artifacts.
    sent_scope = placed.value.request.headers["x-checkout-session"]
    stored_scope = page.evaluate("sessionStorage.getItem('vmarket:checkout-session:v1')")
    assert len(sent_scope) == 64 and sent_scope == stored_scope
    page.reload(wait_until="networkidle")
    assert page.evaluate("sessionStorage.getItem('vmarket:checkout-session:v1')") == stored_scope
    print("PASS: real anonymous storefront checkout sends a stable tab capability through the BFF; tab identity survives reload")
    expect(page.get_by_role("heading", name="Saved checkout confirmed", exact=True)).to_be_visible()
    expect(page.get_by_role("button", name="Place order", exact=True)).to_be_disabled()

    def fill_checkout():
        page.get_by_role("button", name="Add", exact=True).first.click()
        for label, value in [("Full name", "QA Recovery Buyer"), ("Email", "recovery@example.test"),
                             ("Postal code", "100-0001"), ("Prefecture", "Tokyo"),
                             ("City", "Chiyoda-ku"), ("Address line", "Chiyoda 1-1")]:
            page.get_by_label(label, exact=True).fill(value)
        page.locator('input[type="checkbox"]').check()

    page.get_by_role("button", name="Start a new checkout", exact=True).click()
    fill_checkout()
    lost_response = {}

    def commit_then_lose_response(route):
        # Real upstream commit, then drop delivery to the browser. Not a mocked success response.
        response = route.fetch()
        assert response.status == 201
        lost_response.update(response.json())
        route.abort("failed")

    page.route(f"{base}/api/orders", commit_then_lose_response)
    page.get_by_role("button", name="Place order", exact=True).click()
    expect(page.get_by_role("heading", name="Checkout result not confirmed", exact=True)).to_be_visible()
    pending = page.evaluate("JSON.parse(sessionStorage.getItem('vmarket:checkout-operation:v1'))")
    assert pending["phase"] == "unresolved"
    assert not any(field in pending for field in ("customer", "trackingToken", "order", "lines"))
    page.unroute(f"{base}/api/orders", commit_then_lose_response)
    page.reload(wait_until="networkidle")
    assert page.evaluate("JSON.parse(sessionStorage.getItem('vmarket:checkout-operation:v1')).key") == pending["key"]
    expect(page.get_by_role("button", name="Place order", exact=True)).to_be_disabled()
    page.set_viewport_size({"width": 390, "height": 844})
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "Recovery controls overflow mobile"
    for label in ("Check checkout result", "Close unused checkout"):
        assert page.get_by_role("button", name=label, exact=True).bounding_box()["height"] >= 44
    page.locator('section[aria-label="Checkout recovery"]').screenshot(path=str(artifacts / "checkout-unresolved-mobile.png"))
    with page.expect_response(lambda response: response.url.endswith("/api/orders/checkout")) as recovered:
        page.get_by_role("button", name="Check checkout result", exact=True).click()
    assert recovered.value.status == 200 and recovered.value.json() == lost_response
    expect(page.get_by_role("heading", name="Saved checkout confirmed", exact=True)).to_be_visible()
    number = lost_response["orderNumber"]
    assert qa_sql(f"SELECT COUNT(*) FROM customer_order WHERE order_number='{number}';").splitlines()[2].strip() == "1"
    assert qa_sql(f"SELECT COUNT(*) FROM inventory_movement WHERE reference_id='{number}' AND movement_type='ORDER_PLACED';").splitlines()[2].strip() == "1"
    print("PASS: upstream checkout committed, browser delivery aborted, reload + GET recovered same order/token with one stock movement")

    page.set_viewport_size({"width": 1280, "height": 900})
    page.get_by_role("button", name="Start a new checkout", exact=True).click()
    fill_checkout()
    delayed = {}

    def hold_original_request(route):
        # Retain only in memory; simulate an original request that has not reached the BFF yet.
        delayed.update(headers=route.request.headers, payload=route.request.post_data_json)
        route.abort("failed")

    page.route(f"{base}/api/orders", hold_original_request)
    page.get_by_role("button", name="Place order", exact=True).click()
    expect(page.get_by_role("heading", name="Checkout result not confirmed", exact=True)).to_be_visible()
    page.unroute(f"{base}/api/orders", hold_original_request)
    page.reload(wait_until="networkidle")
    with page.expect_response(lambda response: response.url.endswith("/api/orders/checkout")) as not_observed:
        page.get_by_role("button", name="Check checkout result", exact=True).click()
    assert not_observed.value.status == 404
    expect(page.get_by_role("button", name="Place order", exact=True)).to_be_disabled()
    with page.expect_response(lambda response: response.url.endswith("/api/orders/checkout/cancel")) as closed:
        page.get_by_role("button", name="Close unused checkout", exact=True).click()
    assert closed.value.status == 204
    expect(page.get_by_role("heading", name="Unused checkout closed", exact=True)).to_be_visible()
    late = context.request.post(f"{base}/api/orders", headers={key: value for key, value in delayed["headers"].items()
        if key in ("idempotency-key", "x-checkout-session")}, data=delayed["payload"])
    assert late.status == 410 and late.json()["code"] == "CHECKOUT_CANCELLED"
    page.get_by_role("button", name="Edit checkout and continue", exact=True).click()
    expect(page.get_by_role("button", name="Place order", exact=True)).to_be_enabled()
    assert page.evaluate("sessionStorage.getItem('vmarket:checkout-operation:v1')") is None
    print("PASS: 404 did not unlock checkout; durable close + explicit acknowledgement allowed editing, delayed original POST rejected 410")
    assert not page_errors,page_errors
    browser.close()

print("PASS: real BFF async 202, scheduled dispatcher, UI polling, injected FAILED import and UI restart, versioned stock, completed replay, CSRF/logout")
