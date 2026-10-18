"""Disposable-DB proof: kill an owned Java worker after 50 commits during Batch STARTED, then recover."""
import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path
from playwright.sync_api import expect, sync_playwright

phase=sys.argv[1]
base=os.environ.get("BASE_URL","http://127.0.0.1:13002")
artifact=Path(__file__).resolve().parents[1]/"backend"/"target"/"operator-browser"/"started-recovery.json"

def sql(statement):
    container=os.environ["TEST_POSTGRES_CONTAINER"]
    assert container.startswith("vmarket-auth-pg-"),"Only the runner-owned disposable DB is allowed"
    result=subprocess.run(["docker","exec",container,"psql","-U","vmarket","-d","vmarket","-v","ON_ERROR_STOP=1","-At","-c",statement],
        check=True,capture_output=True,text=True,timeout=15)
    return result.stdout.strip()

if phase=="prepare":
    # Java has already been force-stopped by the runner. End the orphaned sleeping test query, not live work.
    job=str(uuid.UUID(json.loads(artifact.read_text(encoding="utf-8"))["jobId"]))
    sql("SET statement_timeout='5s'; SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event='PgSleep' AND query ILIKE '%INSERT INTO inventory_movement%';")
    sql("SET lock_timeout='5s'; DROP TRIGGER IF EXISTS qa_hold_started_import ON inventory_movement; DROP FUNCTION IF EXISTS qa_hold_started_import();")
    # Accelerate only the wait for the production 60-second lease; this is expiry fault injection, not an SLA measurement.
    sql(f"UPDATE import_work_item SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE job_id='{job}' AND state='CLAIMED';")
    assert sql(f"SELECT applied_rows FROM migration_job WHERE id='{job}'")=="50"
    print("PASS: killed worker retained 50 committed rows; orphaned test query ended and lease expiry injected")
    sys.exit(0)

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
        sql("""INSERT INTO product(id,vendor_id,sku,name,category,description,price_minor,image_path,image_alt)
          SELECT md5('qa-crash-'||n)::uuid,'10000000-0000-0000-0000-000000000001','CRASH-'||lpad(n::text,3,'0'),
          'Crash recovery item','OFFICE','Disposable recovery dataset',100,'/products/aeroknit-travel-jacket.jpg','QA item'
          FROM generate_series(1,51) n;
          INSERT INTO inventory_stock(product_id,on_hand) SELECT id,100 FROM product WHERE sku LIKE 'CRASH-%';
          CREATE FUNCTION qa_hold_started_import() RETURNS trigger LANGUAGE plpgsql AS $$
          BEGIN IF NEW.movement_type='LEGACY_IMPORT' AND NEW.product_id=md5('qa-crash-51')::uuid
          THEN PERFORM pg_sleep(30); END IF; RETURN NEW; END; $$;
          CREATE TRIGGER qa_hold_started_import BEFORE INSERT ON inventory_movement FOR EACH ROW EXECUTE FUNCTION qa_hold_started_import();""")
        csv="schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version\n"+"".join(
            f"2,CRASH-{n:03d},NAMI,Recovered item,101,200,0\n" for n in range(1,52))
        response=context.request.post(f"{base}/api/ops/imports",headers={"Content-Type":"text/csv","X-Source-Name":"started-crash-51.csv","X-Source-Encoding":"UTF-8","X-CSRF-TOKEN":csrf,"Prefer":"respond-async"},data=csv)
        assert response.status==202,response.text()
        job=str(uuid.UUID(response.json()["id"]))
        deadline=time.monotonic()+20
        while time.monotonic()<deadline:
            count=sql(f"SELECT applied_rows FROM migration_job WHERE id='{job}'")
            sleeping=sql("SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='PgSleep' AND query ILIKE '%INSERT INTO inventory_movement%'")
            if count=="50" and sleeping=="1":break
            time.sleep(0.2)
        assert count=="50" and sleeping=="1",(count,sleeping)
        execution=sql(f"SELECT e.job_execution_id FROM batch_job_execution e JOIN batch_job_execution_params p ON p.job_execution_id=e.job_execution_id WHERE p.parameter_name='jobId' AND p.parameter_value='{job}' ORDER BY e.job_execution_id DESC LIMIT 1")
        assert sql(f"SELECT status FROM batch_job_execution WHERE job_execution_id={int(execution)}")=="STARTED"
        assert sql(f"SELECT COUNT(*) FROM inventory_movement WHERE reference_id='{job}'")=="50"
        artifact.write_text(json.dumps({"jobId":job,"oldExecutionId":int(execution)}),encoding="utf-8")
        print("PASS: real worker is STARTED, 50 rows committed, second chunk held in disposable DB")
    elif phase=="resume":
        proof=json.loads(artifact.read_text(encoding="utf-8"));job=str(uuid.UUID(proof["jobId"]))
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            response=context.request.get(f"{base}/api/ops/imports")
            assert response.status==200,response.text()
            record=next(item for item in response.json() if item["id"]==job)
            if record["status"]=="COMPLETED" and sql(f"SELECT state FROM import_work_item WHERE job_id='{job}'")=="FINISHED":break
            time.sleep(0.2)
        assert record["status"]=="COMPLETED" and record["appliedRows"]==51,record
        assert sql(f"SELECT state FROM import_work_item WHERE job_id='{job}'")=="FINISHED"
        assert sql(f"SELECT status FROM batch_job_execution WHERE job_execution_id={int(proof['oldExecutionId'])}")=="FAILED"
        assert sql(f"SELECT COUNT(*) FROM inventory_movement WHERE reference_id='{job}'")=="51"
        assert sql("SELECT COUNT(*) FROM inventory_stock s JOIN product p ON p.id=s.product_id WHERE p.sku LIKE 'CRASH-%' AND s.on_hand=101 AND s.version=1")=="51"
        assert sql(f"SELECT COUNT(*)||':'||COUNT(DISTINCT e.job_instance_id) FROM batch_job_execution e JOIN batch_job_execution_params p ON p.job_execution_id=e.job_execution_id WHERE p.parameter_name='jobId' AND p.parameter_value='{job}'")=="2:1"
        assert sql(f"SELECT recovery_count FROM import_work_item WHERE job_id='{job}'")=="1"
        page.get_by_role("button",name="Refresh import runs",exact=True).click()
        article=page.locator("article").filter(has=page.get_by_role("heading",name="started-crash-51.csv",exact=True))
        expect(article.get_by_text("COMPLETED",exact=True)).to_be_visible(timeout=15000)
        page.screenshot(path=str(artifact.parent/"started-recovery-completed.png"),full_page=True)
        print("PASS: STARTED process crash recovered automatically; 51 unique stock movements, one JobInstance/two executions")
    else:raise AssertionError("Unknown recovery QA phase")
    context.close();browser.close()
