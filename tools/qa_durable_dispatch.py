"""Real BFF proof: READY work survives an owned Java process kill/restart (not STARTED recovery)."""
import json
import os
import sys
import time
import csv
import io
import hashlib
from pathlib import Path
from playwright.sync_api import expect, sync_playwright

base=os.environ.get("BASE_URL","http://127.0.0.1:13002")
artifact=Path(__file__).resolve().parents[1]/"backend"/"target"/"operator-browser"/"durable-dispatch.json"
phase=sys.argv[1]
checkout_headers={"Idempotency-Key":os.environ["TEST_CHECKOUT_KEY"],"X-Checkout-Session":os.environ["TEST_CHECKOUT_CAPABILITY"]}
checkout_payload={"customer":{"name":"Restart QA Buyer","email":"restart@example.test","postalCode":"100-0001",
    "prefecture":"Tokyo","city":"Chiyoda-ku","addressLine":"Chiyoda 1-1","paymentMethod":"COD","privacyAccepted":True},
    "lines":[{"sku":"VM-004","quantity":1}]}
with sync_playwright() as playwright:
    browser=playwright.chromium.launch(headless=True)
    context=browser.new_context()
    page=context.new_page()
    page.goto(f"{base}/ops",wait_until="networkidle")
    page.get_by_label("Username").fill(os.environ["TEST_OPERATOR_USERNAME"])
    page.get_by_label("Password",exact=True).fill(os.environ["TEST_OPERATOR_PASSWORD"])
    page.get_by_role("button",name="Sign in",exact=True).click()
    expect(page.get_by_role("heading",name="OPERATIONS LEDGER")).to_be_visible(timeout=20000)
    csrf=context.request.get(f"{base}/api/auth/session").json()["csrfToken"]
    if phase=="stage":
        response=context.request.post(f"{base}/api/ops/imports",headers={"Content-Type":"text/csv","X-Source-Name":"survive-process-restart.csv","X-Source-Encoding":"UTF-8","X-CSRF-TOKEN":csrf,"Prefer":"respond-async"},
            data="schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version\n2,VM-008,ATLAS,Durable toiletry kit,60,5100,0\n")
        assert response.status==202,response.text()
        job=response.json()
        assert job["status"]=="STAGED" and job["appliedRows"]==0,job
        checkout=context.request.post(f"{base}/api/orders",headers=checkout_headers,data=checkout_payload)
        assert checkout.status==201,checkout.text()
        order=checkout.json()
        artifact.write_text(json.dumps({"jobId":job["id"],"checkoutOrder":order["orderNumber"],
            "trackingDigest":hashlib.sha256(order["trackingToken"].encode()).hexdigest()}),encoding="utf-8")
        print("PASS: API acknowledged committed READY work with dispatcher disabled")
    elif phase=="resume":
        job_id=json.loads(artifact.read_text(encoding="utf-8"))["jobId"]
        deadline=time.monotonic()+25
        while time.monotonic()<deadline:
            response=context.request.get(f"{base}/api/ops/imports")
            assert response.status==200,response.text()
            job=next(item for item in response.json() if item["id"]==job_id)
            if job["status"]=="COMPLETED": break
            assert job["status"] in ("STAGED","RUNNING"),job
            time.sleep(0.2)
        assert job["status"]=="COMPLETED" and job["appliedRows"]==1,job
        snapshot=context.request.get(f"{base}/api/ops/inventory/snapshot")
        assert snapshot.status==200
        assert "2,VM-008,ATLAS,Durable toiletry kit,60,5100,1" in snapshot.text()
        proof=json.loads(artifact.read_text(encoding="utf-8"))
        checkout=context.request.post(f"{base}/api/orders",headers=checkout_headers,data=checkout_payload)
        assert checkout.status==201,checkout.text()
        order=checkout.json()
        assert order["orderNumber"]==proof["checkoutOrder"]
        assert hashlib.sha256(order["trackingToken"].encode()).hexdigest()==proof["trackingDigest"]
        stock=next(row for row in csv.reader(io.StringIO(snapshot.text())) if len(row)>1 and row[1]=="VM-004")
        assert stock[4]=="43" and stock[-1]=="1", "Checkout retry after process loss consumed stock again"
        tracked=context.request.get(f"{base}/api/orders/{order['orderNumber']}",params={"trackingToken":order["trackingToken"]})
        assert tracked.status==200
        print("PASS: encrypted checkout response survived forced Java restart; identical tracking capability and stock consumed once")
        replay=context.request.post(f"{base}/api/ops/imports/{job_id}/restart",headers={"X-CSRF-TOKEN":csrf,"Prefer":"respond-async"})
        assert replay.status==200 and replay.json()["appliedRows"]==1,replay.text()
        print("PASS: READY job survived forced Java restart; scheduled dispatcher applied it once with stock version 1")
    else:
        raise AssertionError("Unknown QA phase")
    context.close()
    browser.close()
