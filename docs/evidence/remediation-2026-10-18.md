# V-Market — remediation evidence, đợt đầu

Ngày: 2026-10-18 (Asia/Bangkok). Base commit `e31c7f2` + local working-tree changes; chưa commit/push/deploy. Đây là bằng chứng local, không phải kết quả production.

Kết quả hiện tại xem **MARKET-3 phase 3 — Async dispatch, UI và READY process-restart proof** ở cuối file; các phase trước được giữ để truy vết, không phải giới hạn hiện tại đã được cập nhật ngược trong mọi đoạn lịch sử.

## Verification — đợt correctness đầu tiên

Chạy từ thư mục `backend`:

```powershell
.\mvnw.cmd -q spotless:apply verify
```

Exit code **0**. Surefire: **19 tests, 0 failures, 0 errors, 0 skipped**. Integration tests dùng PostgreSQL 17.6 và Redis 8.2 Testcontainers; validation tests dùng Jakarta/Hibernate Validator thực. Spotless format/check và Maven package đã qua trong verify.

Có thể chạy lại lệnh trên để tái tạo kết quả. Reports local: `backend/target/surefire-reports/TEST-*.xml`; log `backend/target/remediation-verify.log`. Target artifacts không được đưa vào Git.

## Những thay đổi đã xác minh

- Null customer/paymentMethod bị validator chặn trước business service.
- Checkout lấy PostgreSQL transaction advisory lock theo key trước lookup; duplicate đồng thời không chạy tiếp như một order mới.
- Fingerprint gồm customer chuẩn hóa và SKU quantities gộp/sắp xếp; key có payload khác trả 409 `IDEMPOTENCY_CONFLICT`.
- Migration V3 thêm `request_hash` nullable, không sửa V1/V2. Order legacy giữ nguyên; key legacy không có fingerprint bị từ chối replay thay vì đoán request.

| Regression test | Trước sửa | Sau sửa |
| --- | --- | --- |
| `simultaneousDuplicateCheckoutReturnsOneOrderAndConsumesStockOnce` | Duplicate primary key trong một request | Cùng order number; một order; stock giảm một lần |
| `rejectsReusingACheckoutKeyForDifferentQuantities` | 201 trả order cũ cho payload khác | 409, stock không giảm thêm |
| `OrderValidationTest` | Null fields không có constraint violation | Customer/paymentMethod có constraint violation |
| `fingerprintMigrationPreservesAnExistingLegacyOrder` | Test bảo vệ upgrade mới bổ sung | V2 có order → V3 giữ totals và hash null |

Source: [OrderService](../../backend/src/main/java/dev/vmarket/order/OrderService.java), [OrderModels](../../backend/src/main/java/dev/vmarket/order/OrderModels.java), [V3 migration](../../backend/src/main/resources/db/migration/V3__checkout_request_fingerprint.sql), [integration tests](../../backend/src/test/java/dev/vmarket/VMarketApplicationTest.java), [validation tests](../../backend/src/test/java/dev/vmarket/order/OrderValidationTest.java).

## Giới hạn còn lại

Chưa có actor/session scope cho checkout key, tracking token entropy thay thế, retention hoặc complete unknown-operation UI. Backend vẫn yêu cầu migration trước khi chạy code mới. Stale stock snapshot policy đã xử lý trong đợt MARKET-2 bên dưới; restart workflow và cache consistency tổng thể vẫn còn. Advisory lock là serialization theo key, không phải distributed messaging/exactly-once thanh toán.

## AUTH-1 — local operator sessions

Kết quả mới nhất thay thế số test của đợt đầu: `backend/./mvnw.cmd -q spotless:apply verify` exit 0, **25 tests / 0 failures / 0 errors / 0 skipped**. `npm run verify` exit 0: **16 unit tests**, lint, typecheck và production build qua. Log local: `backend/target/auth-verify.log`, `backend/target/auth-frontend-verify.log`.

- Flyway V4 thêm BCrypt accounts, login windows và Spring Session JDBC; không sửa migration đã có.
- BFF/ops secrets không đủ để gọi ops. Không session → 401; VIEWER hoặc thiếu CSRF khi đã login → 403.
- Login đổi session ID; cookie HttpOnly/Secure/SameSite=Lax; session idle timeout 15 phút; logout và disabled account mất quyền; mỗi request đọc role hiện tại trong PostgreSQL.
- Audit fulfillment dùng username từ principal. Không còn actor chung cho thao tác đó.
- BFF forward cookie/CSRF và Set-Cookie; chặn cross-origin writes; frontend có sign-in/sign-out gate cho ops.
- Login 10 attempts/minute/backend peer address, lưu PostgreSQL. Sau BFF, nhiều người có thể chia sẻ budget; không claim per-user-IP throttling.

Regression `operationsRequireAUserSessionEvenWithTrustedServiceCredentials` tái hiện GET ops 200 trước sửa; sau sửa GET/POST đều 401. POST anonymous từng trả 403 do CSRF trước authorization; handler hiện phân biệt 401 anonymous với 403 authenticated. Các test login/rotation, role revoke, disabled account, CSRF/logout chạy qua session thật, không dùng mock principal.

### Real browser evidence

`pwsh -NoProfile -File tools/verify-operator-session.ps1` chạy Next production build + Java JAR với PostgreSQL/Redis disposable. Python Playwright **không mock API**: sign-in → ops visible → cookie HttpOnly/SameSite → no-CSRF POST 403 → sign-out → ops 401; storefront vẫn đọc được. Lệnh này đã qua local. Screenshot/log tái tạo ở `backend/target/operator-browser/` (ignored).

`tools/qa_playwright.py` là **UI fixture suite**, không thay cho smoke backend thật ở trên.

### Scope / rollout

Chưa deploy các thay đổi này. Backend phải apply V4 và có cấu hình operator trước khi dùng console; không có default password, public signup hay password recovery. Secure cookie chỉ tắt cho local HTTP, không được tắt ở production. Cần phối hợp rollout backend/frontend và kiểm tra live. Body limits, timeout contract và các phần MARKET-3–4 vẫn chưa hoàn tất; MARKET-2 được ghi riêng bên dưới. Bảng bootstrap chỉ insert nếu account chưa tồn tại, không reset mật khẩu account hiện hữu.

## Session attribute concurrency follow-up

Regression `concurrentSessionAttributeInsertionDoesNotFailARequest` load hai snapshot của cùng session, mỗi snapshot thêm cùng attribute mới rồi lần lượt save: trước sửa, lần save thứ hai ném PostgreSQL duplicate key; sau sửa cả hai thành công. Dùng `PostgreSqlJdbcIndexedSessionRepositoryCustomizer` chuẩn của Spring Session để upsert attribute; không tự viết session protocol. Backend full verify chạy lại exit 0; số tests mới nhất ở phần AUTH-1 đã cập nhật. Đây là last-write-wins attribute persistence, không phải exactly-once/linearizable sessions.

## MARKET-2 — Versioned stock snapshots và reconciliation có phạm vi

Base vẫn `e31c7f2` + local working tree, **chưa commit/push/deploy**. Kết quả mới nhất thay thế số test của các đợt ở trên:

| Lệnh | Kết quả | Artifact local (ignored, có thể tái tạo) |
| --- | --- | --- |
| Trong `backend`: `.\mvnw.cmd -q spotless:apply verify` | Exit 0; **34 tests**, 0 failures/errors/skipped; PostgreSQL 17.6 + Redis 8.2 Testcontainers | `backend/target/stock-policy-verify.log`, `backend/target/surefire-reports/TEST-*.xml` |
| `npm run verify` | Exit 0; **17 tests**, lint, typecheck, production build | `backend/target/stock-frontend-verify.log` |
| `pwsh -NoProfile -File tools/verify-operator-session.ps1` | Exit 0; **real** browser → Next production BFF → Java JAR → disposable PostgreSQL/Redis; không mock API | `backend/target/stock-browser-verify.log`, `backend/target/operator-browser/` |
| `git diff --check` | Không có whitespace errors | Chạy từ repo root |

Chạy Maven tuần tự, không build lại JAR hoặc `.next` khi browser smoke đang dùng chúng. Runner chỉ tạo/dừng container và process QA do nó sở hữu; không đụng live data. Netty/Lettuce có warning reconnect khi Testcontainers shutdown; không phải test failure. Các red logs dưới đây là regression có chủ đích, không phải kết quả verify cuối.

### Policy và regression evidence

| Scenario / test | Trước sửa đã tái hiện | Sau sửa / assertion |
| --- | --- | --- |
| `anOldVersionedStockSnapshotCannotRestoreAlreadySoldInventory` | Bán 1 còn 31, snapshot cũ khôi phục lên 32 (`stock-snapshot-red.log`) | `STALE_STOCK_VERSION`; stock 31, price không bị đổi, không import movement |
| `duplicateCanonicalSkusInOneSnapshotAreAllQuarantined` | Dòng đầu vẫn được apply (`duplicate-stock-red.log`) | Cả VM-001/vm-001 bị quarantine, applied 0, rejected 2 |
| `operatorsCanExportCurrentVersionsButAnonymousCallersCannot` | Endpoint chưa có, 404 (`stock-export-red.log`) | Anonymous 401; operator nhận CSV có actual stock version từ một SQL snapshot |
| `aLegitimateSaleAfterImportIsChangedSinceImportNotCorruption` | Sale hợp lệ bị báo mismatch (`reconciliation-sale-red.log`) | mismatch 0, changed 1, stock not verified 1 |
| `aNegativeStockVersionIsQuarantinedInsteadOfFailingTheWholeUpload` | Constraint violation làm upload fail (`negative-version-red.log`) | Row invalid được giữ với validation reason; job trả rejected 1, stock không đổi |
| Frontend `loadInventorySnapshot` | Chưa có hàm, Vitest TypeError | CSV giữ nguyên backend version, không chế version tại client |
| Real browser CSV accessible name | Label gồm cả payload, không tìm được trường `CSV payload` | Explicit `aria-labelledby`, exact-label locator hoạt động sau khi export/edit |

Các test coverage bổ sung không được gọi là red→green regression:

- `unversionedLegacyFilesOnlyChangeMetadataAndCannotClaimStockVerification`: legacy `on_hand=9999` không đổi stock/movement; tên/giá cập nhật; report stock unverified 1.
- `checkoutAndStagedSnapshotOnlyProduceTheTwoLegalSerializedOutcomes`: barrier start hai task checkout/import; nếu import thắng stock cuối 39, nếu sale thắng stock 31 và import reject. Assert một order, movement/proof/counters tương ứng. Test cho phép hai thứ tự hợp lệ, không claim ép được PostgreSQL lock wait hay stress/load proof.
- `movementFailureRollsBackTheWholeChunkIncludingProofMetricsAndInvalidation`: trigger test làm lỗi movement dòng hai; stock/price cả chunk, movements, proof markers/counters rollback; applied metric không tăng, Redis sentinel còn nguyên. Trigger/function test được xóa trong `finally`.
- `stockPolicyMigrationPreservesHistoryWithoutInventingVersionProof`: schema riêng ở V4 có stock/version, historical applied row, movement và report; upgrade V5/V6 giữ nguyên stock/version/movement, old job là `LEGACY_UNVERIFIED`, không tự tạo baseline, report cũ có stock unverified 1. Fresh schema cũng qua full integration suite.

### Real browser scenario / UI evidence

Sign-in → export schema v2 từ DB → chỉnh một row target 40 → Stage & apply → tạo order mua 1 qua BFF → stock 39 → Reconcile: mismatch 0 / changed 1 / stock not verified 1 → upload snapshot gốc version 0 bị reject → export lại vẫn stock 39/version 2 → mutation thiếu CSRF 403 → sign-out → ops 401 → public storefront vẫn đọc được. Browser script assert JSON và CSV từ API thật, không chỉ dựa screenshot.

Screenshots tái tạo: `loaded-snapshot.png`, `version-aware-reconciliation.png`, `operations-mobile.png` trong `backend/target/operator-browser/`. Đã inspect desktop/mobile 390px; script assert không horizontal overflow. Panel hướng dẫn phân biệt metadata-only/versioned stock và cảnh báo loading thay nội dung editor; report có scope unknown/changed/unverified không hiển thị biểu tượng blanket green. Skill frontend-design giữ visual language hiện hữu và làm rõ policy; webapp-testing dùng Python Playwright với runner Windows hiện có.

### Contract / giới hạn còn lại

- V5/V6 additive, chưa sửa migration đã deploy. Backend apply schema mới trước; sau đó frontend tương ứng. Old clients vẫn gửi 5 cột được, nhưng **không còn ghi stock**; đây là thay đổi business policy cần thông báo operator.
- Version chỉ là optimistic concurrency token của operator được cấp quyền, không phải chữ ký nguồn vendor. Không gán version mới ở upload để biến file cũ thành an toàn. Stock token không chứng minh metadata freshness; metadata vẫn theo feed được operator chấp nhận.
- Baseline chứng minh giá trị lúc apply và sự thay đổi version về sau, không chứng minh toàn bộ ledger/business correctness. Higher version được đánh dấu changed/unverified, không tự suy mọi thay đổi là hợp lệ. Reconciliation không auto-repair inventory.
- Pending historical rows thiếu contract bị `REUPLOAD_REQUIRED`; duplicate upload cùng checksum hiện vẫn trả old job, chưa có restart workflow. Checksum scope/encoding dedup, worker lease, strict decoder/row limits và validation edge cases còn thuộc MARKET-3/4.
- Import invalidation/metrics đã chuyển sau commit, nhưng checkout invalidation và stale cache refill/TTL chưa giải quyết; DB checkout/export là nguồn authoritative. Không claim cache strong consistency.
- Log real smoke đợt MARKET-2 có warning đăng ký meter `spring.batch.job.active`: Prometheus gặp hai tập tag keys không tương thích từ instrumentation Spring Batch. Phần này đã được sửa và kiểm tra ở MARKET-3 phase 1 bên dưới; không suy metric đúng chỉ vì business test qua.
- Checkout actor scope, tracking entropy, unknown-result UX và các phần còn lại trong plan vẫn chưa xong. Không claim zero technical debt, exactly-once, production scale hay remediation đã live.

## MARKET-3 phase 1 — Controlled restart và Batch observability

Vẫn local working tree trên base `e31c7f2`, chưa commit/push/deploy. **Chỉ hoàn thành recovery từ caught failure; không phải durable background worker/crash recovery.** Không có migration mới trong phase này.

### Kết quả mới nhất

- Trong `backend`: `.\mvnw.cmd -q spotless:apply verify` exit 0; **38 tests**, 0 failures/errors/skipped (34 integration, 2 validation, 2 configuration). Log `backend/target/batch-restart-verify.log`; JUnit XML ở `backend/target/surefire-reports/`.
- `npm run verify` exit 0; **18 tests**, lint/typecheck/production build. Log `backend/target/restart-frontend-verify.log`.
- `pwsh -NoProfile -File tools/verify-operator-session.ps1` exit 0, real Next BFF + Java + disposable PostgreSQL/Redis. Log `backend/target/restart-browser-verify.log`; backend/server logs ở `backend/target/operator-browser/`. Runner Windows hiện có được tái sử dụng, không dùng API fixtures. Backend JAR/Next build không chạy đồng thời với smoke.
- `git diff --check` qua. Artifact trong target ignored, tái tạo bằng các lệnh trên. Logs ERROR từ trigger fault injection trong integration suite là có chủ đích; xem kết quả JUnit/exit code cuối, không nhầm với production errors.

### Red → green

| Scenario | Red đúng nguyên nhân | Sau sửa |
| --- | --- | --- |
| `aFailedBatchExecutionIsReportedAsFailedInsteadOfLeavingAStagedJob` | JobLauncher trả execution FAILED nhưng API trả STAGED (`batch-status-red.log`) | Business status FAILED, stock không đổi, Batch metadata FAILED |
| `aFailed120RowImportRestartsTheSameInstanceWithoutRepeatingCommittedMovements` | Sau caught failure với 50 applied, POST restart 404 (`batch-restart-red.log`) | HTTP 200; 120 applied, đúng 120 movement/stock version 1; cùng một JobInstance, hai executions; gọi lại completed không tạo execution thứ ba |
| Tasklet boundary trong bài 120 dòng | Step commit_count 0 dù 50 business rows đã commit (`batch-boundary-red.log`) | Mỗi invocation một nhóm; step commit_count 1 trước lỗi nhóm tiếp; không giữ outer business DB transaction |
| `aSecondRestartIsRejectedPromptlyWhileTheFirstWorkerHoldsTheJobRow` | Request thứ hai chờ row lock và timeout (`batch-restart-race-red.log`) | NOWAIT + map SQLSTATE 55P03/CannotAcquireLock thành 409; worker đầu hoàn tất, một movement |
| `batchObservationMetersDoNotCollideWithLegacyBatchMeters` | Prometheus registration-failure callback nhận `spring.batch.job.active` tag collision (`batch-metrics-red.log`) | Không registration failure; scrape có `vmarket_import_job_seconds_count` và `vmarket_import_step_seconds_count` |
| Frontend `restartImport` | Vitest TypeError vì chưa có hàm | POST cùng job ID, gửi CSRF từ session, không upload lại file |

Concurrency test dùng advisory lock trong connection test để giữ worker đầu ở movement insert; đợi `pg_blocking_pids` xác nhận **thực sự blocked** rồi gửi restart thứ hai. Không dựa một sleep để giả định overlap. Connection rollback/release và trigger/function cleanup trong `finally`; test chỉ tác động PostgreSQL Testcontainer.

120 dòng dùng 120 synthetic canonical products riêng (`RESTART-*`), mỗi SKU một dòng schema v2, stock 100 → 101. Trigger làm lỗi ở SKU 051, sau 50 dòng đã commit. Restart vẫn kiểm tra expected stock version của các row chưa apply; không gán version mới và không ghi lại nhóm đầu. Các synthetic products chỉ có trong DB test disposable và được dọn trước test kế tiếp.

### API / transaction / metrics contract

- POST upload vẫn tạo resource với HTTP 201; khi Batch execution FAILED/STOPPED, body status FAILED phải được kiểm tra. Launcher throw vẫn đi qua error contract 503 và ghi FAILED; không coi “launcher không throw” là thành công.
- `POST /api/ops/imports/{jobId}/restart` cần OPERATOR + CSRF + trusted BFF/ops credentials. FAILED được claim ngắn trong PostgreSQL rồi chạy lại cùng identifying jobId. Completed/with-errors trả result cũ, không reprocess rejected rows. STAGED/RUNNING hoặc busy lock trả 409. Lỗi DB khác không bị nuốt thành conflict.
- Launch hiện **synchronous**, restart HTTP 200 trả summary sau execution (có thể vẫn FAILED nếu nguyên nhân chưa sửa), không giả làm queue 202. Applied marker là selector resume; checkpoint chỉ để hiển thị progress.
- Tasklet trả CONTINUABLE sau một nhóm và FINISHED khi hết pending; NOT_SUPPORTED cho outer tasklet, REQUIRES_NEW ở chunk service. Metadata Batch vẫn do JobRepository quản lý. Đây không phải rewrite thành ItemReader/Processor/Writer chunk-oriented.
- Giữ legacy `spring.batch.*`; đặt convention Observation là `vmarket.import.job` / `vmarket.import.step`, không sửa/xóa tag để che xung đột, không tắt observability. Test bật observability thật với Prometheus registry vì registry test đơn giản không phát hiện được ràng buộc tag-name này.
- Real smoke tiếp tục toàn bộ stock-policy flow; thêm completed restart qua BFF trả same job/appliedRows, không CSRF 403 và sau logout 401. Caught-failure restart 120 rows là integration proof, **không được mô tả thành browser fault-injection proof**.

### Phần còn lại tại thời điểm phase 1 — xem phase 2 bên dưới cho ownership mới

Chưa có durable dispatch queue/async worker, claim owner/fencing lease/heartbeat, recovery của process bị kill để lại business RUNNING và Batch STARTED, handling các cửa sổ commit giữa business/Batch metadata, đồng thời upload cùng checksum, cancel/STOPPED workflow đầy đủ hoặc nút restart/polling trên UI. Không tự reset job đang active; sau timeout/crash phải review, không blind retry hay sửa Batch metadata. Chưa có production rollout/load proof/exactly-once guarantee. Các phần này là bước tiếp theo, cùng MARKET-4/cache/CSV và các repo còn lại.

## MARKET-3 phase 2 — Durable work ledger và business fencing

Base `e31c7f2` + working tree; **chưa commit/push/deploy**. Phase này bổ sung ownership được kiểm chứng, **không phải hoàn tất async queue/crash recovery**. HTTP upload/restart vẫn synchronous như phase 1, không đổi thành 202.

### Verification mới nhất

| Lệnh | Kết quả | Artifact local ignored |
| --- | --- | --- |
| Trong `backend`: `.\mvnw.cmd -q spotless:apply verify` | Exit 0; **46 tests**, 0 failures/errors/skipped: 42 integration + 2 validation + 2 configuration | `backend/target/import-work-verify.log`, `backend/target/surefire-reports/TEST-*.xml` |
| `npm run verify` | Exit 0; **18 tests**, lint/typecheck/production build | `backend/target/import-work-frontend-verify.log` |
| `pwsh -NoProfile -File tools/verify-operator-session.ps1` | Exit 0; real browser/BFF/Java with V7, PostgreSQL/Redis disposable, không mock API | `backend/target/import-work-browser-verify.log`, `backend/target/operator-browser/` |
| `git diff --check` | Qua | Chạy lại từ repo root |

Backend log của smoke cuối không có ERROR, meter registration warning hay `claimToken` launcher parameter. Full integration suite có ERROR từ trigger fault injection có chủ đích và warning reconnect lúc shutdown container; không lấy những log đó làm kết luận suite fail. Chạy Maven tuần tự, không rebuild JAR/Next lúc smoke đang chạy. Runner Windows hiện hữu được tái sử dụng theo webapp-testing; không truy cập production credentials/data.

### Proof theo scenario

- **Red → green:** `anUploadedImportHasADurableWorkRecordAndTerminalOutcome`: work table chưa tồn tại (`import-work-red.log`); sau V7/staging integration, mỗi upload có work item và hoàn tất FINISHED.
- **Red → green:** `aChunkWithoutAWorkerClaimCannotChangeInventory`: gọi chunk không claim từng vẫn apply (`import-claim-required-red.log`); API nội bộ đã chuyển từ bare jobId sang `ImportWorkClaim`, thiếu token bị chặn và stock/movement không đổi. Các test chunk cũ được cập nhật để lấy claim thật, không bỏ DB assertions.
- **Red → green:** `workerTokensAreNotStoredInBatchParametersThatTheLauncherLogs`: claimToken từng nằm trong JobParameters (`import-token-logging-red.log`); sau sửa chỉ generation **non-identifying** đi cùng execution, token được resolve từ DB với đúng generation. Không tắt log launcher để che token.
- **Coverage:** `expiredOwnershipCanBeFencedWithoutLettingTheOldWorkerAdoptOrFailTheNewGeneration`: đặt lease quá hạn bằng SQL, old chunk bị reject; takeover tạo token/generation mới; old generation không resolve/adopt token mới, không apply hoặc ghi FAILED đè new owner. New owner apply một lần rồi finish; stock/movement/counters đúng. Đây là **simulated lease expiry/control-plane handover**, không phải kill/restart process thật.
- **Coverage:** `losingTheLeaseInsideAChunkRollsBackStockProofAndAfterCommitEffects`: trigger làm lease hết hiệu lực trong movement insert. Guard cuối nhóm từ chối renewal; quantity/price/movement/applied marker/counter rollback, applied metric không tăng và Redis sentinel không bị xóa. Không claim đây là wall-clock timeout/load benchmark.
- **Coverage:** `twoWorkersCompetingForReadyWorkProduceOneOwnerAndOneMovement`: barrier start hai claim; đúng một owner ở generation 1, một movement khi apply. Test không ép một PostgreSQL lock wait; busy-row behavior có test restart NOWAIT riêng từ phase 1.
- **Coverage:** `workMigrationPreservesOldJobsWithoutInventingOwnersForActiveExecutions`: schema V6 có COMPLETED/FAILED/RUNNING, upgrade V7 giữ status/counters/stock; work state tương ứng FINISHED/FAILED/BLOCKED, generation 0 và token/lease null. Không suy quyền sở hữu từ historical RUNNING.
- **Coverage:** `aStagingFailureRollsBackTheJobAndItsDurableWorkTogether`: trigger abort staging sau khi work record đã insert; toàn bộ job/work/staged rows rollback, stock không đổi. Trigger/function test được dọn trong finally; dữ liệu chỉ trong Testcontainer.

Các coverage tests đã xanh khi được thêm để mở rộng bảo vệ cho ownership đã triển khai; không được ghi thành red→green regression giả. Full suite vẫn giữ proof 120 rows → 50 committed → failure → restart cùng JobInstance/two executions/120 movements, auth/CSRF và stock snapshot policy từ các phase trước.

### Contract hiện tại

- [V7](../../backend/src/main/resources/db/migration/V7__durable_import_work.sql) chỉ thêm work ledger/FK/state/claim-shape constraints và indexes; staging + work insert cùng transaction. Business job status khác work state và khác Spring Batch execution status.
- [ImportWorkService](../../backend/src/main/java/dev/vmarket/migration/ImportWorkService.java): lock order **job → work**; normal claim chỉ READY, random token và generation tăng, 60 giây theo `clock_timestamp()` PostgreSQL. Token không xuất hiện trong API summary hoặc Batch JobParameters; generation non-identifying giữ nguyên JobInstance khi restart.
- [ImportChunkService](../../backend/src/main/java/dev/vmarket/migration/ImportChunkService.java) kiểm tra claim sau lock và trước mutations, renew cuối nhóm; renew/finalize thất bại rollback cả transaction. Work row lock được giữ đến commit, không có takeover chen giữa nhóm. Lease check ở các guard, không tuyên bố revoke chính xác tại từng nanosecond wall-clock commit.
- Empty-work finish xóa claim/lease và ghi FINISHED cùng business completion. Caught-failure bookkeeping chỉ cập nhật khi state/token/generation/lease còn đúng; stale/expired/finished owner không ghi FAILED đè owner mới hoặc kết quả đã commit. Không có callback unguarded sau JobLauncher để đồng bộ trạng thái bằng suy đoán.
- `takeOverExpired` là **internal control-plane primitive**, không phải scheduler hay public recovery endpoint. Normal caller không tự adopt expired work. Sau takeover, business writes của generation cũ bị fence; primitive chưa reconcile Batch STARTED, chưa launch execution mới và không bảo vệ toàn bộ metadata Spring Batch khỏi mọi crash window.

### Chưa hoàn tất / rollout

Chưa có background dispatcher/202 API, automatic abandoned-execution recovery, heartbeat thread độc lập, bounded DB query/lock timeouts toàn flow, handling mọi cửa sổ giữa business commit và Batch metadata, concurrent upload checksum race hay UI restart/polling. Lease 60 giây là safety bound cố định: nhóm bị block quá lâu có thể rollback và để work CLAIMED/business RUNNING cho recovery sau; không blind reset/retry. READY work sau crash giữa staging và launch hiện chưa có dispatcher tự lấy. Vì vậy MARKET-3 vẫn mở.

Historical nonterminal jobs ở BLOCKED cần review, không tự xóa applied markers hay gán version/owner. Khi rollout: backup phù hợp → quiesce imports/drain hoặc stop old workers → apply V7 → chạy backend fenced mới → kiểm tra business/work/Batch state → client smoke. Không chạy old unfenced binary song song hoặc downgrade mù khi có active work; additive schema không đồng nghĩa mixed-version/zero-downtime guarantee. Chưa deploy ở phase này, không cần credential nền tảng mới.

## MARKET-3 phase 3 — Async dispatch, UI và READY process-restart proof

Ngày 2026-10-18, base `e31c7f2` + working tree, **chưa commit/push/deploy**. Phase này thay thế giới hạn “HTTP chỉ synchronous / chưa dispatcher / chưa UI restart / concurrent checksum chưa xử lý” ở các phase trước. Không đóng toàn bộ MARKET-3.

### Verification mới nhất

| Lệnh | Kết quả | Artifact local ignored |
| --- | --- | --- |
| Trong `backend`: `.\mvnw.cmd -q spotless:apply verify` | Exit 0; **52 tests**, 0 failures/errors/skipped: 48 integration + 2 validation + 2 configuration | `backend/target/async-import-verify.log`, `backend/target/surefire-reports/TEST-*.xml` |
| `npm run verify` | Exit 0; **22 tests**, lint/typecheck/production build | `backend/target/async-import-frontend-verify.log` |
| `pwsh -NoProfile -File tools/verify-operator-session.ps1` | Exit 0; real Next production BFF/Java/PostgreSQL/Redis; READY forced-process-restart + scheduled dispatch + UI FAILED restart | `backend/target/async-import-browser-verify.log`, `backend/target/operator-browser/` |
| `docker compose config --quiet`; `git diff --check` | Qua | Không in rendered config/secret values |

Backend Testcontainers dùng PostgreSQL 17.6/Redis 8.2. Suite giữ scheduler disabled bằng test property để chủ động gọi dispatcher và kiểm tra DB invariants, không giả là test scheduled timing. Real process/browser runner bật scheduler sau restart để kiểm tra đường chạy tự động; không rebuild Maven/Next khi server đang dùng JAR/build. Các file log red dưới đây là regression có chủ đích. Phase trước có test integration fault injection; phase này **real browser cũng chủ động tạo lỗi DB**, nên `ERROR: QA controlled import failure` trong resumed-backend log là expected, không được mô tả log hoàn toàn không ERROR.

### Red → green và coverage

| Test / behavior | Đã tái hiện trước sửa | Kết quả sau sửa |
| --- | --- | --- |
| `asynchronousUploadReturnsDurablyStagedWorkWithoutRunningItInTheRequest` | Prefer respond-async vẫn chạy synchronous, 201 (`async-upload-red.log`) | 202/Preference-Applied; STAGED/READY và stock chưa đổi, chưa Batch execution khi dispatcher disabled |
| `dispatcherConsumesCommittedAsyncWorkButLeavesLegacyReadyWorkAlone` | Chưa có dispatcher bean (`import-dispatch-red.log`) | Dispatcher xử lý async job, giữ legacy READY không dispatch; lần poll tiếp không tạo movement nữa |
| `asyncRestartQueuesExistingFailedJobAndOnlyTheDispatcherAppliesIt` | Restart chạy synchronous, không trả queued 202 (`async-restart-red.log`) | Cùng job ID được requeue; queued restart tiếp trả 409; dispatcher apply; completed restart trả 200 và không apply lại |
| `concurrentDuplicateAsyncUploadsCreateOneDurableJobInsteadOfFailingWithDuplicateChecksum` | Concurrent upload bị duplicate checksum constraint (`async-checksum-race-red.log`) | Cùng job ID; một migration job/work/staged row; không chạy stock trong request |
| `competingDispatchersConsumeAQueuedJobOnlyOnce` | Một lần full-suite phát hiện candidate snapshot cũ làm worker thua ném ImportConflict dù test riêng trước đó qua | Candidate yêu cầu business STAGED; sau job lock re-read work bằng statement READ COMMITTED mới; hai calls kết thúc bình thường, một generation/execution/movement |
| TypeScript async request/restart/BFF | Request thiếu Prefer; BFF bỏ Prefer; polling helper chưa có | Unit tests trước sửa fail đúng nguyên nhân; sau sửa giữ 202 body STAGED, forward preference, restart cùng identity/CSRF, polling chỉ active và dừng khi đọc lỗi |

`dispatchMigrationDoesNotEnqueueExistingSynchronousReadyWork` là **coverage**, không giả thành red→green: schema riêng ở V7 có READY/STAGED work, upgrade V8 giữ state/generation/stock và `dispatch_requested=FALSE`. Full suite kiểm tra cả fresh DB và các đường upgrade đã có. Các race tests dùng barrier + real DB assertions, không phải load/latency guarantee hay proof của mọi interleaving.

### API / worker / UI contract

- [V8](../../backend/src/main/resources/db/migration/V8__import_dispatch_intent.sql) thêm dispatch intent default false; không đổi historical work thành queue tự chạy. `Prefer: respond-async` là opt-in trên API; UI hiện luôn gửi preference cho upload/restart. BFF forward cả Prefer và Preference-Applied.
- Upload async parse/validate và commit staging/work trước response 202. CSV vẫn được đọc trong request; không claim streaming/body-limit toàn ingress đã hoàn tất. 202 xác nhận có job, **không xác nhận apply/success**; scheduler có thể đã xử lý nhanh trước khi client đọc summary. Upload duplicate trả existing job, không tự restart failed job hay biến historical synchronous READY thành dispatchable work.
- Caught FAILED restart chuyển work READY/dispatch=true và business STAGED trong cùng short transaction, trả 202; active/queued/busy trả 409; completed replay trả 200, không launch. Client không gửi preference vẫn giữ synchronous upload 201/restart 200 để tương thích. No automatic mutation retry; lost response hướng dẫn re-read status.
- [ImportDispatcher](../../backend/src/main/java/dev/vmarket/migration/ImportDispatcher.java) chạy fixed-delay: startup 2 giây, delay 1 giây sau tick trước kết thúc, một claimed item mỗi tick. Job-row SKIP LOCKED và re-check sau lock; [ImportJobRunner](../../backend/src/main/java/dev/vmarket/migration/ImportJobRunner.java) chạy Batch bằng claim hiện hành. Không có in-memory queue/unbounded executor, không retry FAILED tự động. Lỗi dispatch được log có giới hạn, không in arbitrary exception message/token/body. Caught Batch failure vẫn lưu trạng thái FAILED bằng owner-fenced bookkeeping.
- `IMPORT_WORKER_ENABLED=true` mặc định backend, có wiring Compose; false chỉ pause dispatch, không trả completion giả. Đây là backend env, frontend `.env` không tự cấu hình Java. Một worker xử lý tuần tự từng job, scale qua instance/claim; chưa đo throughput hoặc scheduler/query SLA. Slow/hung DB call có thể giữ scheduler, chưa có full timeout bounds.
- [OperationsConsole](../../components/operations-console.tsx) giữ style industrial dark theo frontend-design: Stage & queue, acknowledgement/job ID, active-state polling 2 giây ở visible tab, manual refresh, FAILED restart, counts/quarantine status. Polling dừng sau lỗi đọc và không chạy background tab; focus/manual refresh có thể đọc lại. Không reconcile khi job còn STAGED/RUNNING. Danh sách chỉ latest 20; job cũ hơn hoặc stalled ownership cần review, không phải full recovery/pagination console.

### Real process / browser evidence

Runner chỉ tác động process/container QA nó sở hữu; dữ liệu disposable, không dùng account/DB production:

1. Start Java với dispatcher disabled, Next BFF và PostgreSQL/Redis mới. `qa_durable_dispatch.py stage` đăng nhập thật rồi POST CSV async VM-008 target 60; assert 202/STAGED/applied 0, giữ job ID ở artifact (không lưu secret/session).
2. Runner **force-kill Java PID của chính nó**, giữ nguyên DB/Redis/Next; start process Java mới với dispatch enabled. Script resume đăng nhập, poll job cũ tới COMPLETED; CSV export phải có VM-008 stock 60/version 1; completed restart replay giữ một applied row. Proof này là crash ở **READY-before-launch**, không phải kill giữa Batch STARTED.
3. `qa_operator_session.py` chạy browser thật: export/edit snapshot → UI Stage & queue 202 → scheduled worker → UI polling tới COMPLETED → sale → scoped reconciliation → stale queued snapshot rejected → completed restart replay.
4. Trong DB QA, script tạo trigger gây lỗi có chủ đích chỉ cho import VM-007. Upload qua UI 202 → worker/Batch FAILED với applied 0 → polling hiện nút Restart failed import → screenshot → gỡ trigger trong finally → click restart từ UI → 202 → scheduled worker COMPLETED. SQL QA assert đúng **một** LEGACY_IMPORT movement. Giữ cùng job ID; không upload lại CSV. Auth/CSRF/logout/public-storefront checks tiếp tục qua.
5. Desktop và mobile 390px có screenshot; script assert không horizontal overflow, restart controls dùng được trên mobile và không có browser pageerror. Screenshots `failed-import-restart.png`, `failed-import-mobile.png`, `operations-mobile.png` trong `backend/target/operator-browser/`; đã inspect layout. Screenshot không thay assertions JSON/CSV/SQL.

webapp-testing dùng Python Playwright, với runner Windows hiện có (đã mở rộng lifecycle riêng, không dùng fixture APIs); frontend-design giữ visual language và làm rõ trạng thái queued so với completed. Các UI contract tests cũ với fixtures vẫn là fixture proof, không bị gọi nhầm thành backend evidence.

### Chưa hoàn tất / rollout

**CLAIMED/RUNNING + abandoned Batch STARTED vẫn chưa tự recover**, kể cả process chết giữa business commit và Batch metadata update. Normal dispatcher chỉ lấy READY, không reset lease/metadata mù. `takeOverExpired` vẫn chỉ internal ownership primitive, chưa có authenticated recovery workflow/metadata fencing hoàn chỉnh; independent heartbeat, full DB lock/query/ingress timeout bounds và recovery của mọi commit window còn mở. Không claim exactly-once, complete crash resilience, production scale, zero technical debt hay toàn bộ MARKET-3 đã xong.

Dedup vẫn theo byte checksum global, chưa chốt semantic source/vendor/schema/encoding scope; strict CSV decoding/limits và cache freshness vẫn MARKET-4. UI chỉ recent 20 và caught-failure restart, không có toàn bộ lịch sử hoặc lease admin console. Auth/operator/rollout constraints từ các phase trước vẫn áp dụng.

Trước deploy: backup dữ liệu cần giữ → pause/drain imports và old unfenced binaries → apply additive V7/V8 → deploy backend/worker mới → smoke → async frontend. Không hạ xuống backend không biết Prefer khi đang có queue, không tự xóa work/Batch metadata hoặc reset RUNNING. Migrations/additive schema không bảo đảm mixed-version zero-downtime. Chưa deploy phase này, không cần thêm key nền tảng.

## MARKET-3 phase 4 — Bounded STARTED recovery và Batch metadata fencing

Ngày 2026-10-18, base `e31c7f2` + working tree, **chưa commit/push/deploy**. Phần này thay thế giới hạn “async CLAIMED/STARTED chưa tự recover” và “empty chunk release FINISHED trước Batch completion” của các phase trước; không đóng mọi yêu cầu MARKET-3.

### Verification mới nhất

| Lệnh | Kết quả | Artifact local ignored |
| --- | --- | --- |
| Trong `backend`: `.\mvnw.cmd -q spotless:apply verify` | Exit 0; **64 tests**, 0 failures/errors/skipped: 60 integration + 2 validation + 2 configuration | `backend/target/started-recovery-verify.log`, `backend/target/surefire-reports/TEST-*.xml` |
| `npm run verify` | Exit 0; **22 tests**, lint/typecheck/production build | `backend/target/started-recovery-frontend-verify.log` |
| `pwsh -NoProfile -File tools/verify-operator-session.ps1` | Exit 0; READY restart + real STARTED Java crash/recovery + existing BFF/UI failure/restart/CSRF/logout/mobile checks | `backend/target/started-recovery-browser-verify.log`, `backend/target/operator-browser/` |
| `docker compose config --quiet`; `git diff --check` | Qua; không in secret/config render | — |

Testcontainers là PostgreSQL 17.6/Redis 8.2 thật; scheduler disabled trong integration tests để điều khiển claim/expiry chính xác. Đây không phải test scheduler timing. Full verify giữ các test upgrade từ schema cũ; V9 được apply cùng các migrations mới. Maven/Next build hoàn tất trước khi chạy JAR/browser; không rebuild khi process QA dùng artifact.

### Red → green và coverage bổ sung

| Test / behavior | Trước sửa | Sau sửa / evidence |
| --- | --- | --- |
| `importMetadataCannotCreateAnExecutionWithoutItsCurrentWorkerScope` | Chưa có fenced repository boundary (`import-metadata-scope-red.log`) | Không có current worker scope thì không được tạo execution (`import-metadata-scope-green.log`) |
| `completedImportAlsoCompletesItsFencedBatchMetadataBeforeReleasingOwnership` | Business complete nhưng metadata vẫn STARTED vì owner đã release quá sớm (`import-metadata-finalization-red.log`) | Retain claim qua metadata finalization, rồi mới FINISHED (`import-metadata-finalization-green.log`) |
| `dispatcherRecoversAnExpiredStartedExecutionWithoutRepeatingItsCommittedMovement` | Dispatcher bỏ qua expired CLAIMED (`import-started-recovery-red.log`) | Retire execution cũ, requeue và hoàn tất cùng JobInstance; không movement trùng (`import-started-recovery-green.log`) |
| `recoveryCanHandleAnotherCrashAfterClaimButBeforeANewExecutionIsCreated` | Lower-generation FAILED execution bị hiểu nhầm mismatch và BLOCKED (`import-prelaunch-recovery-red.log`) | Cho phép prior retired execution, không cho generation mismatch tùy ý (`import-prelaunch-recovery-green.log`) |
| `aRecoveryFailureDoesNotStarveOtherReadyJobs` | Fault injection ở recovery audit làm dispatcher dừng trước claim READY (`import-recovery-isolation-red.log`) | Recovery rollback riêng, log loại lỗi không raw message; READY khác vẫn FINISHED (`import-recovery-isolation-green.log`) |

Test thiếu bean ở scope boundary là kiểm tra wiring ban đầu; không gọi nó là proof race runtime. Lần thử test isolation dùng Mockito ban đầu không compile vì repo không có dependency đó; đã bỏ cách thử này, không thêm dependency chỉ để mock. Red/green isolation cuối cùng ở trên là test với DB trigger thật, fail đúng exception từ audit update, không phải compile failure.

Các test sau là **coverage cho behavior đã có**, không giả thành red→green:

- `recoveredWorkerCannotRewriteJobStepOrExecutionContextMetadata`: generation cũ không update job/step/context hoặc tạo execution mới; context cũ được giữ, old failure callback không đè FINISHED. Log `import-stale-metadata-coverage.log`.
- `recoveryDoesNotStealALiveLeaseOrAJobRowHeldByAnInFlightTransaction`: live owner không bị lấy; job-row đang lock được SKIP LOCKED, call trả trong timeout test; metadata vẫn STARTED đến khi lock được giải phóng. Log `import-live-owner-coverage.log`.
- `recoveryResumesThePendingRowAfterFiftyRealStockMovementsAlreadyCommitted`: 51 stock rows, commit 50 trước expiry; recovery chỉ áp dụng dòng còn lại, 51 movements/stock version 1, hai executions cùng instance. Log `import-partial-started-coverage.log`.
- `recoveryMetadataRetirementAndGenerationRevocationRollBackTogetherOnFailure`: trigger lỗi sau các metadata update nhưng trước work audit commit; job/step vẫn STARTED và claim generation/token vẫn giữ nguyên sau rollback. Gỡ fault rồi recovery qua. Log `import-recovery-atomic-coverage.log`.
- `recoveryAcknowledgesCompletedMetadataWithoutLaunchingAnotherExecution`: completed job/step nhưng chưa release claim; expiry → FINISHED acknowledgement, vẫn một execution/movement. Log `import-completed-ack-coverage.log`.
- `repeatedOwnerLossStopsAfterThreeRecoveriesAndRequiresReview`: ba lần READY/recovery; mất owner lần thứ tư → BLOCKED, không stock mutation. Log `import-recovery-limit-coverage.log`.
- `unknownBatchMetadataIsBlockedRatherThanBlindlyRestarted`: UNKNOWN → BLOCKED, không launch thêm hay apply stock. Chạy trong full verify.

### Ownership / transaction contract

1. [ImportRepositoryConfiguration](../../backend/src/main/java/dev/vmarket/migration/ImportRepositoryConfiguration.java) tạo standard JDBC JobRepository delegate có propagation REQUIRED, không độc lập commit khi outer fencing/recovery transaction đang giữ lock. Boot framework repository không phải repository dùng trong job/step/launcher import; tất cả runtime references được qualifier rõ ràng.
2. [ImportRepositoryFence](../../backend/src/main/java/dev/vmarket/migration/ImportRepositoryFence.java) yêu cầu [ImportExecutionScope](../../backend/src/main/java/dev/vmarket/migration/ImportExecutionScope.java), matching job/generation và current claim. Mỗi mutation: REQUIRES_NEW → job/work lock → verify owner → standard repository SQL → verify owner → commit. Token không là JobParameter; read metadata vẫn bình thường, worker delete history bị cấm. Launcher chạy synchronous để scope không biến mất ở executor khác.
3. Business empty chunk ghi completion nhưng chỉ renew, **không release claim**. Runner chỉ FINISHED sau khi Batch COMPLETED đã commit, business complete và không valid unapplied rows. Lỗi acknowledgement sau Batch COMPLETED không ghi business FAILED đè kết quả; expired claim được recovery ack.
4. [ImportRecoveryService](../../backend/src/main/java/dev/vmarket/migration/ImportRecoveryService.java) chỉ chọn async CLAIMED hết lease; lock job SKIP LOCKED rồi re-check/lock work. Matching active execution/step được retire FAILED qua standard raw delegate trong cùng transaction với generation revocation, clear token/lease và READY/STAGED. Applied markers/context/history không bị xóa. New execution giữ identifying jobId, generation non-identifying.
5. Completed metadata nhất quán → FINISHED ack. UNKNOWN/ABANDONED, generation mismatch hay completed metadata mâu thuẫn → BLOCKED/business FAILED để review. Lower-generation FAILED/STOPPED được chấp nhận cho crash trước execution mới. Không có execution vẫn là valid pre-launch case. Tối đa ba recovery; owner loss kế tiếp BLOCKED. [V9](../../backend/src/main/resources/db/migration/V9__import_recovery_audit.sql) lưu count/time/reason; đây là bounded state/audit summary, không phải full append-only recovery event history.
6. Metadata/recovery có transaction timeout 15s, PostgreSQL lock timeout 3s/statement timeout 10s. Business chunk/ingress chưa có full timeout bounds; không gọi toàn flow là bounded. Normal success giữ state FINISHED, caught failure không tự retry. Recovery exception rollback riêng và không cản READY khác; damaged recovery candidate vẫn cần review, có thể lặp lại lỗi mỗi tick.

### Real STARTED process-crash scenario

[Runner](../../tools/verify-operator-session.ps1) dùng Next production BFF, Java JAR, PostgreSQL/Redis disposable; không account/data production. [qa_started_recovery.py](../../tools/qa_started_recovery.py) chạy sau READY restart proof:

1. Sign in qua browser/BFF, tạo 51 sản phẩm `CRASH-*` chỉ trong DB QA. Trigger sleep 30s ở movement dòng 51 tạo cửa sổ quan sát: Batch STARTED, 50 applied rows và 50 committed movements; nhóm thứ hai còn đang transaction.
2. Runner force-kill **đúng Java PID nó tạo**, giữ DB/Redis/Next. Script terminate backend PostgreSQL đang giữ **test-only sleeping query** trong DB disposable, gỡ trigger, xác nhận 50 rows còn nguyên; inject lease expiry bằng SQL. Việc kết thúc orphaned test query và expiry injection được ghi rõ: không giả là proof query tự timeout/natural 60-second lease hay đo recovery SLA.
3. Start Java mới với scheduler enabled. Không gọi public restart endpoint để cứu job này. BFF polling thấy cùng job COMPLETED/applied 51; SQL assert FINISHED, old execution FAILED, đúng 51 movements, cả 51 stock=101/version=1, hai executions/một JobInstance, recovery_count=1. Screenshot `backend/target/operator-browser/started-recovery-completed.png`; ID artifact `started-recovery.json` không chứa session/token.

Runner tổng đã kết thúc **exit 0**. READY-before-launch proof và STARTED proof đều qua; suite operator tiếp theo cũng qua 202/scheduler/UI polling, versioned stock/reconciliation/stale rejection, injected FAILED → UI restart → COMPLETED, completed replay, CSRF/logout/public access. Mobile 390px không overflow và không browser pageerror theo assertions của suite. Đã xem ảnh `started-recovery-completed.png`: cùng job `started-crash-51.csv` hiện COMPLETED/applied 51; screenshot là layout evidence, SQL/API assertions mới là correctness evidence. Các process/container QA đã được runner dọn; không còn listener QA 18082/13002 hoặc container `vmarket-auth-*` do lượt chạy này để lại.

Log test có controlled DB failures chủ động; full Maven log cũng có warning khi Redis Testcontainer đã dừng và client shutdown/reconnect. Exit 0 cùng Surefire reports không failures/errors/skipped là kết quả suite, không có nghĩa log hoàn toàn sạch ERROR/WARN. Những fault-injection logs không được dùng làm chứng cứ hệ thống production từng gặp lỗi này.

### Giới hạn / rollout

Independent heartbeat, full business-query/ingress limits, mọi crash/connection-loss interleaving, concurrent recovery stress, pagination/recovery admin UI và semantic checksum scope vẫn mở. Sync non-dispatched expired work/historical BLOCKED không tự bị scheduler lấy. Không claim exactly-once, production scale/SLA, zero technical debt hay toàn bộ MARKET-3 đã xong.

Trước deploy: backup → pause/drain imports → **stop toàn bộ old metadata/business writers không fenced** → apply V7–V9 → start backend mới → kiểm tra work/Batch/business và smoke → async frontend. Không mixed-version zero-downtime guarantee; không truncate Batch history/reset RUNNING để né owner check. Phase này chưa commit/push/deploy.

## MARKET-4 / API-1 follow-up — Metadata-only cache, strict bounded CSV và body budgets

Ngày 2026-10-18, base `e31c7f2` + working tree. **Chưa commit/push/deploy**, không sửa repo name/URL hay đọc credential production. Phần này thay thế các ghi chú cũ “catalog availability vẫn cache sau checkout / strict decoder-row limits chưa làm”. Những giới hạn khác của checkout/recovery vẫn giữ nguyên.

### Verification

| Lệnh | Kết quả | Artifact local ignored |
| --- | --- | --- |
| Trong `backend`: `.\mvnw.cmd -q spotless:apply verify` | Exit 0; **78 tests**, 0 failures/errors/skipped: 72 PostgreSQL/Redis integration + 2 body-limit unit + 2 validation + 2 production configuration | `backend/target/cache-csv-verify.log`, `backend/target/surefire-reports/TEST-*.xml` |
| `npm run verify` | Exit 0; **26 tests**, lint/typecheck/production build | `backend/target/cache-csv-frontend-verify.log` |
| `pwsh -NoProfile -File tools/verify-operator-session.ps1` | Exit 0; previous READY/STARTED crash scenarios, added invalid-file/413/cache-stock checks and existing operator/mobile suite | `backend/target/cache-csv-browser-verify.log`, `backend/target/operator-browser/` |
| `docker compose config --quiet`; `git diff --check`; Python AST parse of updated QA script | Qua | Không in rendered config/secret values; AST check không tạo pycache |

Maven và Next build chạy xong trước browser runner; không rebuild artifact khi Java/Next đang dùng. Testcontainers PostgreSQL 17.6/Redis 8.2 dùng dữ liệu disposable, scheduler disabled trong integration tests. Runner riêng bật scheduler và dùng các process/container do nó sở hữu. Không sửa dữ liệu live. Test fault-injection và Redis shutdown/reconnect warnings vẫn có trong full-suite logs; kết quả pass không có nghĩa log hoàn toàn không ERROR/WARN.

### Red → green đã tái hiện

| Test / contract | Trước sửa | Sau sửa |
| --- | --- | --- |
| `aWarmCatalogCacheCannotHideStockConsumedByCheckout` | GET sau sale vẫn trả 32 thay vì 31 (`catalog-stock-freshness-red.log`) | Metadata-only cache, stock từ DB mỗi GET (`catalog-stock-freshness-green.log`) |
| `malformedUtf8IsRejectedBeforeAnyImportJobIsStaged` | Byte 0xFF bị thay ký tự, upload vẫn 202 (`csv-strict-decoder-red.log`) | REPORT decoder → 400 INVALID_IMPORT; không job/work (`csv-strict-decoder-green.log`) |
| `duplicateHeadersCannotSilentlyChooseOneOfTwoStockColumns` | Hai cột on_hand vẫn 202 (`csv-duplicate-header-red.log`) | DISALLOW duplicate headers → 400 trước staging (`csv-duplicate-header-green.log`) |
| `extraCsvValuesAreRejectedInsteadOfSilentlyDiscarded` | Field thừa bị bỏ qua, 202 (`csv-row-shape-red.log`) | Record width phải khớp header → 400 (`csv-row-shape-green.log`) |
| `anImportOverOneThousandDataRowsIsRejectedBeforeStaging` | 1.001 records vẫn được stage (`csv-row-limit-red.log`) | Từ chối trước mọi insert, không staged rows/job (`csv-row-limit-green.log`) |
| `aDuplicateUploadCannotBypassEncodingValidation` | Existing checksum khiến UTF-16 không hỗ trợ vẫn được trả 202 (`csv-dedup-validation-red.log`) | Canonical encoding validation trước lookup → 400 (`csv-dedup-validation-green.log`) |
| `byteIdenticalUploadsWithDifferentDeclaredEncodingsConflict` | Cùng ASCII bytes khai báo UTF-8/CP932 đều trả job cũ mà không báo mâu thuẫn (`csv-encoding-identity-red.log`) | 409 IMPORT_CONFLICT, vẫn một job; recheck cả trong staging lock (`csv-encoding-identity-green.log`) |
| `oversizedCsvHasAnExplicitPayloadLimitResponse` | Service chỉ từ chối 400 sau body conversion (`backend-body-limit-red.log`) | Java controller read budget → 413 REQUEST_TOO_LARGE (`backend-body-limit-green.log`) |
| BFF oversized unknown-length CSV | Proxy đọc toàn arrayBuffer rồi gọi fetch upstream (`bff-body-limit-red.log`) | Count/cancel stream; 413 trước fetch (`bff-body-limit-green.log`) |
| `aConverterCannotBypassTheBudgetBySkippingInputBytes` | FilterInputStream mặc định skip bỏ qua counter (`backend-skip-budget-red.log`) | Override skip/read, cùng byte budget (`backend-skip-budget-green.log`) |

Các log red trên là failure đúng behavior, không compile/dependency failure. TDD skill hướng dẫn từng bước red→green rồi refactor; body budgets được tách vào `RequestBodyLimits` để import service không phụ thuộc MVC advice. `backend-body-limit-refactor.log` kiểm tra lại unit tests sau refactor.

### Coverage, không giả thành red→green

- `malformedCp932AlsoRejectsTheWholeFileBeforeStaging`: byte pair 0x81/0x20 không hợp lệ → 400, không job. Existing valid Japanese CP932 test tiếp tục qua. Log `csv-cp932-coverage.log`.
- `theOneThousandRowBoundaryIsAcceptedWithoutExecutingInTheRequest`: đúng 1.000 records trả 202/applied 0 và stage đủ 1.000; dataset cố ý duplicate SKU nên cả 1.000 bị quarantine. Đây là boundary/intake proof, không phải proof import 1.000 sản phẩm unique thành công hay benchmark. Log `csv-row-boundary-coverage.log`.
- `catalogCacheContainsOnlyMetadataAndCannotKeepAnInactiveProductVisible`: inspect actual Redis JSON không có availableQuantity; sau deactivate product, cached entry không còn xuất hiện ở GET. Test restore flag trong finally. Log `catalog-metadata-eligibility-coverage.log`.
- `unavailableRedisDoesNotPreventDatabaseCatalogReadsOrCorrectCheckout`: injected StringRedisTemplate double ném connection failure ở read/write, với PostgreSQL/checkout thật; catalog vẫn đọc stock 32→31. Đây là **client-failure policy double**, không phải dừng Redis container/network chaos test. Log `catalog-cache-fallback-coverage.log`.
- `RequestBodyLimitAdviceTest`: unknown-length JSON stream bị dừng sau 65.537 bytes, không đọc hết source 100.000 bytes; skip không bypass budget. Log `backend-unknown-length-coverage.log` và skip logs. Đây là unit stream proof, không giả là HTTP chunked network test.
- BFF coverage: đúng 5.000.000 bytes được forward nguyên vẹn (có byte CP932), 5.000.001 bị chặn; producer unknown-length được cancel trước khi drain toàn stream; JSON boundary 65.536/65.537. Logs `bff-binary-boundary-coverage.log`, `bff-stream-cancellation-coverage.log`, `bff-json-boundary-coverage.log`. Lần đầu so sánh deep-equality 5 MB làm test timeout; đã thay bằng native full-byte Buffer equality cùng length check, không tăng timeout/giảm byte assertions hoặc sửa application để né test.

Existing rollback tests được đổi cache key sang v2 vì storage contract đã đổi; vẫn assert sentinel giữ nguyên sau rollback. Không xóa assertion để làm xanh suite. Old v1 entries không dùng nữa, không cần destructive Redis flush hoặc DB migration.

### Contract / giới hạn được chốt

1. [CatalogCache](../../backend/src/main/java/dev/vmarket/catalog/CatalogCache.java) lưu metadata trong `vmarket:catalog:metadata:v2`, TTL 5 phút, không quantity. [CatalogQueryService](../../backend/src/main/java/dev/vmarket/catalog/CatalogQueryService.java) luôn query DB stock + active product/vendor rồi merge SKU. Inactive cached entries bị filter; cache miss/error fallback DB. Stock response là snapshot, không reservation; checkout vẫn lock/reload price và inventory.
2. Metadata name/price/list có thể stale đến lần refresh, gồm late reader-fill sau invalidation. TTL tính từ cache write, không phải hard freshness SLA từ mutation commit. New list entries có thể chờ refresh. Không thêm cache generation system chỉ để nhận strong consistency; không claim strong consistency.
3. [LegacyImportService](../../backend/src/main/java/dev/vmarket/migration/LegacyImportService.java) REPORT decoding; duplicate headers, inconsistent width hoặc malformed CSV reject toàn file trước staging. Required headers vẫn bắt buộc; unique extra columns được chấp nhận nhưng every row phải đúng header width. Structurally valid business errors vẫn quarantine theo row. Tối đa 1.000 records và 5.000.000 bytes; không streaming migration lớn hoặc latency guarantee.
4. Giữ checksum global/raw-byte hiện có để rename không chạy lại file. Filename chỉ label; vendor/schema nằm trong payload bytes. Validate supported/canonical encoding trước duplicate lookup và trong recheck; conflicting encoding → 409 review. Khác byte/newline/actual encoding có thể tạo job khác, không semantic/vendor-scoped dedup; không thay unique constraints hay backfill dữ liệu cũ.
5. [BFF](../../lib/backend-proxy.ts) đếm bytes khi đọc, cancel oversize stream không chờ untrusted cancellation hook; giữ nguyên binary payload. CSV 5.000.000 bytes, other bodies 65.536. [RequestBodyLimitAdvice](../../backend/src/main/java/dev/vmarket/shared/RequestBodyLimitAdvice.java) độc lập đếm Java controller read/skip trước conversion, check cả declared length. Handler trả 413 REQUEST_TOO_LARGE không echo input. Đây là byte budget tại application, không bảo đảm provider không buffer trước, không deadline/slow-client defense hoàn chỉnh. Servlet form-login không qua RequestBodyAdvice, dù BFF vẫn giới hạn body.

### Real browser / process proof mới nhất

[qa_operator_session.py](../../tools/qa_operator_session.py) chạy sau READY/STARTED recovery scenarios trên cùng disposable stack:

1. Login browser thật; UTF-8 malformed bytes, duplicate headers và extra row field qua BFF đều HTTP 400/INVALID_IMPORT. Oversized CSV qua BFF HTTP 413.
2. Gọi Java trực tiếp bằng operator session/CSRF và service credentials chỉ trong memory của runner: cùng oversized CSV HTTP 413, độc lập với BFF. Recent job ID set trước/sau các invalid requests không đổi. Không print session/secret.
3. Export versioned snapshot → import VM-001 stock 40 → GET catalog làm nóng cache → sale một đơn vị → GET catalog trả 39. Không invalidate stock cache vì Redis không còn giữ stock.
4. Previous FAILED UI restart, completed replay, stale snapshot rejection, reconciliation, CSRF/logout và mobile overflow/pageerror assertions tiếp tục qua. READY và STARTED Java process crash proofs cũng chạy lại qua; STARTED proof vẫn có explicit expiry injection/test-query termination, không natural-lease/SLA measurement.

Runner exit 0 và cleanup containers/processes; screenshots trong target là minh họa layout hiện có, correctness dựa API/SQL/assertions. webapp-testing skill dùng Python Playwright và runner Windows hiện có để quản lý process-owned lifecycle; không fixture API cho lượt này.

### Backlog tiếp theo

MARKET-1 actor/session-scoped checkout identity, key lifecycle, tracking capability entropy và unknown-outcome/reload UX vẫn chưa xử lý. API-1 timeout/correlation/error contracts đầy đủ, import heartbeat/full query bounds/recovery UI và rollout còn mở. PULSE-1/2/3/4 và CORE-3/4 không bị đánh dấu done bởi lượt sửa V-Market này. Không claim toàn bộ repo hết bug/technical debt hoặc current live đã có các thay đổi.

## MARKET-1 phase 2 — Guest-scoped identity, random tracking và protected replay lifecycle

Ngày 2026-10-18, base `e31c7f2` + working tree; **chưa commit/push/deploy**. Phần này thay thế giới hạn cũ “chưa có guest scope/tracking entropy/backend replay lifecycle”. Không đóng toàn bộ MARKET-1: unresolved operation key/payload persistence và reload UX vẫn mở.

### Verification

| Lệnh | Kết quả | Artifact local ignored |
| --- | --- | --- |
| Trong `backend`: `.\mvnw.cmd -q spotless:apply verify` | Exit 0; **88 tests**, 0 failures/errors/skipped: 80 PostgreSQL/Redis integration + 2 crypto + 2 body-limit + 2 validation + 2 production configuration | `backend/target/checkout-identity-verify.log`, `backend/target/surefire-reports/TEST-*.xml` |
| `npm run verify` | Exit 0; **29 tests**, lint/typecheck/production build | `backend/target/checkout-identity-frontend-verify.log` |
| `pwsh -NoProfile -File tools/verify-operator-session.ps1` | Exit 0; encrypted checkout response survives owned Java force-kill/restart; same-operation BFF retry + anonymous UI checkout/tab identity; previous import/auth/cache/mobile checks | `backend/target/checkout-identity-browser-verify.log`, `backend/target/operator-browser/` |
| `docker compose config --quiet`; `helm lint infra/helm/v-market`; `git diff --check`; Python AST parse | Qua; Helm chỉ có advisory icon recommendation | Không in config render/secret values |

Maven/Next build hoàn tất trước JAR/browser runner. Lượt full Maven cuối tăng assertion upgrade về owner/key/hash/ciphertext, không đổi production code sau browser proof. Full-suite logs vẫn gồm controlled faults và Redis shutdown warnings như ghi ở các phase trước; pass được xác định từ exit/report, không phải giả nhận mọi log sạch. Không đọc `.env` hay gọi nền tảng production để kiểm tra phase này.

### Red → green

| Behavior / test | Trước sửa | Sau sửa / log |
| --- | --- | --- |
| `differentGuestCapabilitiesCannotReplayEachOthersCheckoutKey` | Hai guest cùng raw key nhận cùng order, stock chỉ trừ một dù là hai namespaces (`checkout-guest-scope-red.log`) | Hai scopes có hai orders độc lập; cùng scope/key vẫn replay (`checkout-guest-scope-green.log`) |
| `trackingCapabilityIsIndependentOfTheCheckoutKeyButStableOnRetry` | Token bằng SHA-256 tracking prefix + stored checkout key (`checkout-tracking-entropy-red.log`) | SecureRandom 32-byte token; retry decrypt cùng token, không sale lần hai (`checkout-tracking-entropy-green.log`) |
| `anExpiredCheckoutKeyCannotCreateAnotherOrderOrInvalidateItsOriginalTrackingLink` | Checkout 25 giờ vẫn replay 201 (`checkout-replay-expiry-red.log`) | 409, giữ order/key/hash và original tracking GET 200 (`checkout-replay-expiry-green.log`) |
| `productionRejectsTheDevelopmentKeyEvenWithAlternativeBase64Padding` | Bỏ padding của development key qua được string-equality guard (`checkout-replay-key-guard-red.log`) | Guard kiểm tra decoded zero bytes (`checkout-replay-key-guard-green.log`) |
| `aHistoricalUnscopedKeyRequiresReviewInsteadOfCreatingAnotherScopedOrder` | Legacy raw key bị coi là một scoped operation mới, tạo order thứ hai (`checkout-legacy-key-red.log`) | Fail closed 409; một order/stock movement, tracking cũ vẫn đọc (`checkout-legacy-key-green.log`) |
| `expiredReplayCiphertextIsPurgedWithoutDeletingTheOrderKeyOrTrackingHash` | Chưa có retention entry point (`checkout-replay-retention-red.log`) | Registered cleanup xóa expired copy, không xóa live copy/order/key/hash (`checkout-replay-retention-green.log`) |
| BFF guest capability forwarding | Allowlist bỏ X-Checkout-Session (`checkout-scope-forwarding-red.log`) | Forward guest header, không cấp ops identity (`checkout-scope-forwarding-green.log`) |
| Browser checkout scope | Request không gửi tab capability (`checkout-browser-scope-red.log`) | Persist/read capability và giữ nguyên header qua retry (`checkout-browser-scope-green.log`) |

Retention red ban đầu là **missing bean/wiring**, không bị gọi nhầm là race proof. Bounded/lock behavior được xác minh riêng bằng real DB coverage bên dưới. Những existing order fixtures được thêm guest header vì API contract đổi; concurrent-duplicate assertion được chuyển sang physical hashed key/scoped record, không còn tìm raw key mà schema mới không lưu. Assertions về một order, stock, payload conflict và oversell tiếp tục giữ.

### Coverage cho behavior đã có

- `CheckoutTrackingTokensTest`: round-trip đúng; wrong order AAD, ciphertext/tag bị sửa hoặc key khác đều 409. Không log decrypted token/key. Log `checkout-replay-integrity-coverage.log`.
- `malformedOrMissingGuestCapabilitiesCannotCreateCheckoutWork`: null/missing, blank, sai độ dài hoặc nonhex bị 400, không order/stock mutation. Log `checkout-invalid-guest-coverage.log`.
- `corruptReplayStorageCannotRotateTheTrackingCapabilityOrRepeatTheSale`: corrupt DB ciphertext → 409, không tạo order/token mới; original tracking hash vẫn lookup 200. Log `checkout-corrupt-replay-coverage.log`.
- `replayCleanupIsBoundedAndSkipsAnExpiredOrderLockedByAnotherTransaction`: synthetic retention fixtures có 101 expired copies + một live; giữ lock một expired row, cleanup chỉ purge 100 row khác, không chờ lock; release rồi lượt tiếp purge phần còn lại, tất cả 102 order/key records vẫn còn. Cloned fixture ciphertext không là valid tracking response cho clone IDs và không được dùng để chứng minh checkout; đây là test cleanup storage selection. Log `checkout-bounded-retention-coverage.log`.
- `fingerprintMigrationPreservesAnExistingLegacyOrder`: DB schema từ V2 lên latest V12 giữ raw legacy key/tracking hash/total; request hash, guest scope và protected response vẫn NULL, không backfill owner/token giả. Chạy trong full verify.
- Browser client invalid stored capability không tự rotate hay gửi checkout. Log `checkout-invalid-tab-coverage.log`. Đây không phải guarantee khôi phục identity sau người dùng xóa toàn bộ tab storage.

### Contract đã triển khai

1. [OrderController](../../backend/src/main/java/dev/vmarket/order/OrderController.java) yêu cầu valid guest header khi tạo checkout. [OrderService](../../backend/src/main/java/dev/vmarket/order/OrderService.java) hash capability, rồi hash tuple domain `guest-checkout:v1` + scope + raw key; advisory lock/unique DB identity theo physical hash. Request fingerprint vẫn gồm normalized customer và SKU quantities. Replay giữ original order/token/totals, trả trạng thái nghiệp vụ hiện tại; không phải cam kết mọi byte response bất biến sau fulfillment changes.
2. Guest capability 32 random bytes tạo phía browser, giữ trong sessionStorage theo tab; không là verified user account hoặc HttpOnly login session. BFF chỉ forward, không tự xác nhận danh tính customer/ops. New raw capability/request key không lưu DB. Storage/XSS/cloned tabs là bearer boundary; không có cross-device guest account recovery.
3. [CheckoutTrackingTokens](../../backend/src/main/java/dev/vmarket/order/CheckoutTrackingTokens.java): random token 32 bytes/64 hex; lookup SHA-256 giữ tương thích. Standard JDK AES-256-GCM dùng random IV 12 bytes, tag 128 bits và domain/order UUID AAD; packet được Base64url để lưu. Sai key/missing/malformed/tampered copy hoặc lookup hash mismatch → 409, không reissue token. [V11](../../backend/src/main/resources/db/migration/V11__protected_checkout_replay.sql) chỉ thêm nullable encrypted copy; [V10](../../backend/src/main/resources/db/migration/V10__guest_checkout_scope.sql) chỉ thêm nullable scope.
4. Replay gate là `created_at > clock_timestamp() - 24 hours`; creation time lấy từ DB. Đây là decision-at-read, không hard cutoff trên byte tới client. Hết window không xóa key để cho execute lại. Order/key tombstones/hash giữ trong lifetime của retained business order; chưa thiết kế full business-order deletion policy.
5. [CheckoutReplayRetention](../../backend/src/main/java/dev/vmarket/order/CheckoutReplayRetention.java) chạy mỗi 60 giây sau startup delay 60 giây, tối đa 100 expired ciphertexts/lượt, FOR UPDATE SKIP LOCKED trong transaction có timeout 15s/lock 3s/statement 10s. [V12](../../backend/src/main/resources/db/migration/V12__checkout_replay_retention_index.sql) thêm partial index. Cleanup failures retry tick sau với sanitized warning; backlog/outage có thể giữ encrypted copies lâu hơn 24h, nhưng API không mở rộng replay window. Không claim purge deadline/SLA.
6. Existing unscoped raw key bị chặn trước create mới; không adopt historical account ownership. Không thay old tracking hashes hoặc tạo token từ key để phục vụ legacy replay. Trường hợp replay unavailable/expired hướng operator review hoặc original tracking link, không tự tạo order mới.

### Real process / browser evidence

Runner Windows sở hữu PostgreSQL/Redis/Next/Java disposable, không production credentials:

1. `qa_durable_dispatch.py stage` tạo một checkout VM-004 bằng ephemeral guest capability từ env của runner. Artifact chỉ lưu order number và SHA-256 của tracking token, không token/capability/customer payload. Đồng thời stage READY import như trước.
2. Runner force-kill đúng Java PID nó tạo, giữ DB/Redis/BFF. Java process mới với cùng replay key nhận lại cùng guest/key/payload qua BFF → **cùng order và cùng tracking capability digest**, stock VM-004 còn 43/version 1 (seed 44), tracking GET 200. Điều này kiểm tra durable encrypted replay qua process boundary, không chỉ map/cache trong JVM.
3. Main operator script gửi lại một completed checkout cùng identity/payload → cùng JSON và SQL một order; thiếu guest header → 400. Đây là client retry mô phỏng mất response bằng cùng request, **không** network packet-drop chaos test.
4. Sau operator logout, browser public storefront thật Add → fill checkout → Place order 201. Header capability khớp sessionStorage; reload vẫn cùng tab identity. Không chụp screenshot checkout chứa bearer token thật. Đây chỉ là **identity** persistence, không phải unresolved operation-key recovery sau reload.
5. READY/STARTED import recovery, strict CSV/body budget, cache stock, FAILED UI restart, CSRF/logout, public reads và mobile assertions trước đó vẫn qua. STARTED proof vẫn explicit expiry injection/test-query termination, không đo natural lease/SLA.

webapp-testing skill dùng Python Playwright với process-owned runner hiện có; TDD giữ regression red→green và phân biệt coverage/wiring proof. Không sửa UI layout trong phase này; không thêm animation/design mới.

### Secret / rollout / phần còn lại

Thêm backend-only `CHECKOUT_REPLAY_KEY`: base64 32 random bytes, generate một lần vào secret store và giữ stable giữa replicas/restarts. Public zero key trong config/example **chỉ local**, production constructor từ chối. Compose/Helm đã có wiring; **chưa provision secret trên hosting, chưa deploy**. Không cần mua domain/nền tảng hoặc AWS cho việc này.

Chưa có keyring/re-encryption: đổi/mất key làm replay cũ fail closed, không làm original tracking link mất hiệu lực. Không rotate active key mà không giữ decrypt capability hoặc lên maintenance/replay-window plan. Stop old checkout writers, pause mutations → backup/apply V10–V12 → configure secret/backend + matching client header → smoke → mở checkout. Không promise mixed-version zero downtime; old clients thiếu header sẽ 400.

**MARKET-1 còn mở:** persist immutable operation identity/payload fingerprint qua unknown outcome/reload, status recovery UX, handling cart/customer edits khi request cũ chưa rõ, BFF/network/checkout lock waiting bounds. Browser hiện vẫn giữ operation key trong component memory; chỉ guest capability đã tồn tại qua reload. Không claim hoàn thành toàn bộ ba repo hoặc zero technical debt/exactly-once.

## MARKET-1 phase 3 — Unknown-outcome checkout / reload recovery

Phần này cập nhật trạng thái sau phase 2 phía trên: operation persistence và recovery UX đã được triển khai local, không còn chỉ giữ key trong component memory. Chưa commit/push/deploy.

### Contract và giới hạn

- `lib/checkout/spec.ts` định nghĩa Zod input/storage/response/result; `handler.ts` trả typed Result, không để network/storage exceptions thoát ra hook. UUID, canonical payload hash, capability hash, phase và timestamp được write/read-back vào sessionStorage **trước POST**. Không lưu customer payload/cart/tracking token trong operation record. Hash là derived data, không phải cam kết anonymization của mọi thông tin đầu vào.
- Hook restore không auto-submit; unresolved/confirmed state khóa form/cart, confirmed/closed cần acknowledgement rõ ràng. GET recovery gửi cùng guest/key nhưng không cần lưu lại thông tin khách. Response schema sai, storage hỏng/không ghi được hoặc identity đổi → giữ/block operation, không tự tạo key mới. Compare-current checks chặn old component response ghi đè operation mới. Đây là best-effort state trong cùng tab, không là transaction/CAS atomic xuyên nhiều browser agents, không chịu được deliberate storage wipe/XSS.
- GET `/api/orders/checkout`: committed order trong replay window → 200; chưa quan sát order → 404 `CHECKOUT_NOT_OBSERVED`; closed key → 410 `CHECKOUT_CANCELLED`. **404 không chứng minh request cũ đã thất bại hoặc sẽ không đến.** Expired/unavailable encrypted replay vẫn fail closed theo phase 2.
- POST `/api/orders/checkout/cancel`: cùng physical-key advisory lock với create, reject existing/historical order 409, insert V13 closed hash rồi commit 204. Late create bị 410, không hủy/refund order. Lock 3s, statement 8s, transaction 10s là cấu hình close path; không được diễn giải thành hard end-to-end SLA.
- Dấu đóng không bị evict/TTL-delete vì điều đó cho phép request cũ tạo sale. Shared advisory capacity lock serialize count+insert, tối đa **10.000 hashes** qua application close writers; đầy → 503 `CHECKOUT_CLOSE_CAPACITY`, giữ unresolved client key. Đóng lại key đã đóng vẫn 204. Đây là demo DB storage budget, không giải quyết denial of service; direct DBA writes ngoài invariant này và distributed ingress rate limiting chưa thuộc proof.
- Browser AbortSignal 15s là unknown outcome, không hủy backend work. BFF upstream/read deadlines và checkout-create lock bounds vẫn mở. Clearing/cloning tab storage hoặc đổi thiết bị không được khôi phục như account login. Guest bearer/tracking capability không được log/chụp screenshot thật.

### Verification

| Lệnh | Kết quả | Artifact local ignored |
| --- | --- | --- |
| Trong backend: `.\mvnw.cmd -q spotless:apply verify` | Exit 0; **93 tests**, 0 failures/errors: 85 PostgreSQL/Redis integration + 8 focused crypto/body/validation/config tests | `backend/target/checkout-recovery-verify.log`, Surefire XML |
| `npm run verify` | Exit 0; **38 tests**, lint/typecheck/production build; includes scoped GET/close BFF routes | `backend/target/checkout-recovery-frontend-verify.log` |
| `pwsh -NoProfile -File tools/verify-operator-session.ps1` | Real disposable Java/PostgreSQL/Redis/BFF/browser checks; details below | `backend/target/checkout-recovery-browser-verify.log`, `backend/target/operator-browser/` |
| Compose config, Helm lint, Python AST, git diff check | Qua; Helm icon recommendation only | Không render secret config |

### Red → green và coverage

- Scoped GET read: trước sửa route recovery trả 400 thay vì 200; sau sửa same guest/key nhận cùng committed order/response, wrong guest 404, stock một lần. Logs `checkout-recovery-read-red.log`/`-green.log`.
- Durable unused-key closure: trước sửa endpoint chưa tồn tại; sau sửa close 204 → delayed POST 410, không order/stock mutation. Logs `checkout-close-unused-red.log`/`-green.log`. Đây là endpoint wiring regression, không gọi nó là concurrency proof.
- Closure capacity race: 9.999 seeded synthetic hashes + hai concurrent closes trước sửa trả 204/204; sau sửa 204/503 và đúng 10.000 records; repeat winning key vẫn 204. Logs `checkout-close-capacity-red.log`/`-green.log`.
- Browser restore, pre-send intent persistence, lost-response GET recovery, explicit close/ack, stale component response và guest identity swap đều có incremental red/green artifacts theo prefix `checkout-tab-restore`, `checkout-intent-persist`, `checkout-lost-response-recovery`, `checkout-close-ack`, `checkout-stale-client-response`, `checkout-identity-swap`.
- Guest đổi trong lúc HTTP đang chạy: trước sửa vẫn confirm success; sau sửa `IDENTITY_CHANGED`, metadata vẫn unresolved. Logs `checkout-response-identity-red.log`/`-green.log`.
- Existing-behavior coverage không giả tạo red: payload edit bị chặn không request, GET 404/ack không xóa key, lost close response → GET 410 khôi phục closed (`checkout-unknown-outcome-coverage.log`). Backend existing-order close 409, closure không ảnh hưởng guest khác, concurrent create/close chỉ có thể 201/409 hoặc 410/204, order/stock theo winning operation; chạy trong final full suite.

### Real browser/transport evidence

1. Runner owned services xác minh forced Java restart/READY và STARTED recovery như phase trước, strict CSV, independent BFF/Java 413, warm cache stock, operator CSRF/logout/FAILED restart. Không thay đổi phạm vi lease-expiry injection của STARTED proof.
2. Anonymous UI checkout 201 → reload giữ guest và confirmed marker, Place order disabled tới acknowledgement.
3. Playwright `route.fetch()` cho request POST đi qua **BFF/Java thật, commit thật**, sau đó `route.abort()` cắt delivery về browser. Đây là application-transport fault injection, không packet-level network chaos. UI unresolved; reload giữ operation key, không tự gửi POST. GET recovery trả đúng JSON order/token đã commit; SQL một customer_order và một ORDER_PLACED movement cho order đó. So sánh token/capability chỉ trong memory, không ghi artifacts.
4. Scenario khác giữ original POST trước khi tới BFF, rồi abort delivery. Sau reload, GET 404 không unlock. Close 204 → closed UI → gửi held original request thật tới BFF bị 410. Explicit acknowledgement mới cho sửa/submit. Không gọi close là cancel/refund.
5. Mobile 390px không horizontal overflow, hai recovery targets cao tối thiểu 44px. Screenshot chỉ recovery panel, không bearer token (`operator-browser/checkout-unresolved-mobile.png`). Fixture token trong `qa_playwright.py` đổi sang 64 hex theo response contract; fixture test không thay thế real backend evidence.

### Dependency/deployment gate

Thêm direct Zod 4.6.7 cho runtime contracts. So sánh lockfile với HEAD không có existing package path nào đổi version; thêm/rehoist nested entries và npm peer metadata. Audit artifact hiện ghi **19 findings: 3 moderate, 14 high, 2 critical**, không có finding cho Zod; các affected packages có Next, proxy-addr và OpenNext/Cloudflare/toolchain. Số finding không đồng nghĩa 19 exploitable runtime bugs; chưa có exposure triage hoàn chỉnh. **Không deploy** trước khi triage/update và verify compatibility. Không chạy blind `audit fix --force` trong feature phase.

Rollout bổ sung V13: stop old checkout writers, pause mutations, backup/migrate V10–V13, giữ replay key stable, backend/client cùng contract rồi smoke trước mở lại. Không rollback sang binary bỏ qua closed-intent checks trong khi vẫn cho checkout writes. Remaining: dependency remediation, bounded BFF/create waiting/error contract/ingress policy, broader storage-failure coverage; V-Pulse/V-Core backlog vẫn theo overall plan. Không claim toàn bộ ba repo hoàn thiện hoặc zero technical debt.

## Dependency follow-up — V-Market frontend / Cloudflare toolchain

Phase này xử lý dependency gate đã ghi phía trên, không thay Java business code và chưa deploy. Baseline audit lưu trong `backend/target/dependency-audit-before.json`: **19 affected package entries (3 moderate, 14 high, 2 critical)**. Đây là số package bị advisory propagation, không phải 19 independent exploitable application bugs.

### Thay đổi có chọn lọc

- Next 16.3.0 → **16.4.2**, OpenNext Cloudflare 1.20.2 → **1.20.12**, Wrangler 4.120.0 → **4.151.1**, Vitest 4.1.10 → **4.1.12**, Sharp override 0.35.3 → **0.35.5**. Registry peer requirements của adapter mới: Next `>=15.5.28 <16 || >=16.4.1`, Wrangler `^4.148.0`; Node 24 của repo đáp ứng Wrangler Node >=22.
- Bỏ override ép `@node-minify/core`/`terser` xuống v8; adapter mới dùng core 9.0.2/terser 9.0.1. Express transitive resolves 5.3.0, proxy-addr 2.0.8, qs 6.16.1. Cập nhật source-map-js trong compatible range lên 1.2.2.
- Core 9.0.2 vẫn exact-pin Glob 10.3.3: scoped override **chỉ `@node-minify/core → glob@10.5.0`**, không downgrade adapter hoặc ép Glob v13 vào consumer v10. Theo [maintainer advisory](https://github.com/isaacs/node-glob/security/advisories/GHSA-5j98-mcp5-4vw2), lỗi liên quan Glob CLI `--cmd`, không core library API; dù repo không chủ động dùng CLI pattern này, vẫn bỏ vulnerable package. Glob v10 còn deprecation warning; scoped override cần được bỏ khi upstream pin được sửa, không tuyên bố mọi dependency đều đang ở major mới nhất.
- Bỏ global `brace-expansion` override. Worker build thực tế bắt được lỗi ESM default export khi ép minimatch v9 dùng brace-expansion v5. Sau sửa, minimatch v9 → brace-expansion **2.1.7**, minimatch v10 → **5.0.12**, đều đúng major API và audit hiện không còn advisory. Không dùng blanket cross-major override để làm đẹp audit.
- Thêm `npm run build:worker` và CI step build Worker **không deploy** sau Node/browser verification. `QA_ARTIFACT_DIR` cho fixture Playwright giữ screenshot lần kiểm tra vào ignored target thay vì ghi đè historical live evidence.

### Triage / nguồn đối chiếu

[Next maintainer advisory về next/og](https://github.com/vercel/next.js/security/advisories/GHSA-vcvr-r3jv-pc5j) áp dụng khi attacker-controlled values đi vào SVG ImageResponse trên Node; repo không dùng `next/og`/ImageResponse. Điều này không phủ nhận các advisories khác trong bản cũ, đặc biệt image optimization; vì vậy chọn upgrade thay vì waive audit. Các phát hiện proxy-addr/toolchain cũng không được gọi là confirmed remote exploit của Java API. [Cloudflare OpenNext docs](https://developers.cloudflare.com/workers/framework-guides/web-apps/opennext/) và registry adapter peer constraints được đối chiếu trước build. Không đổi framework/hosting theo recommendation dành cho new apps.

### Bằng chứng trước/sau

| Check | Kết quả / artifact ignored |
| --- | --- |
| Baseline audit | 19 findings, `dependency-audit-before.json`; CI high-level audit gate không qua trên baseline |
| Intermediate Worker regression | Global brace-expansion v5 → `SyntaxError: ... does not provide an export named default`; `dependency-worker-brace-red.log` |
| Final `npm ci` | Exit 0, clean reinstall, 0 reported vulnerabilities; `dependency-ci-install.log` |
| Final `npm run verify` | Exit 0, **38 frontend tests**, lint/typecheck/Next 16.4.2 production build; `dependency-frontend-verify.log` |
| `npm run build:worker` | Exit 0, `.open-next/worker.js`; `dependency-worker-build.log` |
| `npx wrangler deploy --dry-run --outdir backend/target/dependency-worker-dry-run` | Exit 0, local bundling only; gzip ~1,129 KiB, no upload; `dependency-worker-dry-run.log` |
| Local Wrangler/workerd + fixture Playwright | Exit 0; storefront checkout, tracking, operations and desktop/mobile/image checks; `dependency-worker-browser.log`, `dependency-worker-browser/*.png` |
| `pwsh -NoProfile -File tools/verify-operator-session.ps1` | Exit 0; real Node BFF + unchanged Java JAR + disposable PostgreSQL/Redis/browser; `dependency-real-browser-verify.log` |
| Final `npm audit --json` | Exit 0, **0 findings at every severity** at verification time; `dependency-audit-after.json` |
| CI YAML parse / Python AST / git diff check | Qua; không đồng nghĩa đã chạy remote GitHub Actions |

Initial Worker browser run qua skill helper bị Page.goto/networkidle timeout. Sau thất bại mới kiểm tra helper và thấy stdout/stderr PIPE cùng shell lifecycle trên Windows; không quy kết đây là lỗi ứng dụng. Chuyển sang owned process với stdout/stderr file, GET `/` 200 (~578ms), cùng fixture suite pass trong ~6.5s. Đây phù hợp với khả năng output/lifecycle của helper gây nghẽn, không là proof root cause packet-level. Dọn cả original child tree và replacement runner-owned process sau QA. Không sửa skill hệ thống hoặc bỏ assertion networkidle để che lỗi.

Fixture suite kiểm tra Worker runtime/UI nhưng mock API contracts; **không** chứng minh Worker → production Java networking. Real Java suite riêng dùng Node BFF và giữ bằng chứng order/token recovery, 404/close/late 410, một stock movement, READY/STARTED crash/recovery (vẫn expiry injection), failed import UI restart, cache stock, auth/CSRF/logout. Java source không đổi trong phase này nên không chạy lại Maven; **93 backend tests** là kết quả phase 3 trước đó, không phải test mới của dependency upgrade.

Không có production account/API deploy mutation. Build OpenNext cảnh báo Windows chưa fully supported; native Windows build/workerd QA lần này vẫn pass, CI Worker build được đặt trên Ubuntu. Vitest còn warning về config loader native của future Vite major, không bị suppress; current 4.1.12 tests/build pass. Audit zero là snapshot advisory database, không cam kết zero security bugs. Dependency gate của V-Market đã qua local; còn bounded BFF/create waits, ingress policy, rollout migrations/secrets và backlog V-Core/V-Pulse; chưa đủ để gọi toàn bộ đợt sửa đã deploy.

## API-1 follow-up — BFF deadlines / bounded responses / unknown outcomes

Phase này đóng BFF waiting gap, **không** đóng Java checkout lock/pool/query gap. Không đổi Java source/schema, không push/deploy, không gọi nền tảng production.

### Contract

`lib/backend-proxy.ts` áp dụng hai budget riêng: **10s đọc request body**, rồi **12s upstream fetch + toàn bộ response body**. CSV upload vẫn 5.000.000 bytes, bodies khác 65.536 bytes. Response buffer tối đa **5.000.000 bytes**, hoàn tất trước khi trả header cho browser; không forward partial 201 rồi mới phát hiện timeout. Abort controller + race kết thúc thời gian chờ, reader cancellation không chờ untrusted cancel hook, timer được dọn trong finally. Không auto-retry fetch/mutation.

| Trường hợp | BFF status / code |
| --- | --- |
| Upload hết giờ | 408 `REQUEST_BODY_TIMEOUT`, không dispatch backend |
| Input không đọc được / quá byte budget | 400 `REQUEST_BODY_UNREADABLE` / 413 `REQUEST_TOO_LARGE` |
| Upstream header/body hết giờ | 504 `BACKEND_TIMEOUT` |
| Network error hoặc upstream 5xx | Sanitized 503 `BACKEND_UNAVAILABLE`, không chuyển stack/HTML/error cookies; bao gồm backend-specific 503 codes |
| Response vượt budget / malformed declared JSON hoặc invalid UTF-8 | 502 `BACKEND_RESPONSE_TOO_LARGE` / `BACKEND_INVALID_RESPONSE` |
| Business 4xx hợp lệ | Giữ status/body; JSON chỉ kiểm tra syntax/UTF-8, không schema nghiệp vụ |

Mọi kết quả trên no-store. CSV binary bytes, valid JSON, session/rotated cookies và Prefer response header tiếp tục được giữ ở normal path. HEAD/204/304 không bị ép JSON body. Vì buffer trước forwarding, proxy này không dùng cho SSE/large download streaming. Request-ID chỉ tiếp tục được forward ở normal response; BFF-generated failure correlation chưa triển khai đầy đủ.

Timeout/5xx/invalid response **không** là bằng chứng rollback hay không tạo order. Client giữ operation identity, restore không auto POST, GET cùng guest/key để kiểm tra; không rotate key để “sửa” timeout. Budgets tính từ khi handler thực thi, không bao gồm pre-handler queue/ingress, synchronous event-loop stalls, aggregate concurrent buffer memory hoặc JDBC pool/lock/query waits. Browser abort/connection abort không tương đương transaction cancellation. Render cold start có thể vượt budget; sau warm-up cần check lại operation cũ. Rate/concurrency limits và pagination cho lớn hơn response budget vẫn thuộc backlog.

### TDD / regressions

Incremental red → minimal fix → green, logs ignored trong `backend/target/`:

- `bff-network-red/green.log`: network TypeError trước sửa reject ra ngoài; sau sửa sanitized 503, một fetch, không leak connection details.
- `bff-deadline-red/green.log`: upstream không có AbortSignal/deadline; sau sửa 12s → abort + 504, không timer còn lại.
- `bff-response-deadline-red/green.log`: trước sửa headers 201 được trả ngay khi response stream còn treo; sau sửa chờ toàn body, 504/cancel nếu hết deadline.
- `bff-response-budget-red/green.log`: 5.000.001-byte response trước sửa vẫn 200; sau sửa 502 thay vì buffer/forward vô hạn.
- `bff-upload-deadline-red/green.log`: stalled upload trước sửa không cancel; sau sửa 10s/408, không fetch.
- `bff-server-error-red/green.log`: upstream private 500 body trước sửa bị forward; sau sửa generic 503 và không forward error cookies.
- `bff-json-response-red/green.log`: malformed declared JSON trước sửa vẫn 201; sau sửa 502.
- Existing-behavior coverage, không giả tạo red: unreadable producer → sanitized 400; 400/401/403/404/409 valid JSON/status/request ID được giữ, timers sạch; client BFF 504 → same-key GET recovery, không POST mới (`bff-error-coverage.log`, `bff-recovery-coverage.log`).

Full verify ban đầu còn phát hiện ESLint quét `.wrangler` generated bundles và `backend/target` dry-run JS (28k lint errors). Thêm đúng hai generated directory ignores; không ignore production source hoặc tắt lint rules. `bff-generated-artifact-lint-red.log` giữ failure, `bff-generated-artifact-lint-green.log` ghi pass sau build/dry-run artifacts tồn tại.

### Real Java commit / delayed response proof

`tools/qa_bff_deadline.py` là **local-only QA bridge**, không nằm trong production routing. `verify-operator-session.ps1` chạy normal Java/BFF/browser suite trước, sau đó restart đúng frontend process nó sở hữu với BACKEND_ORIGIN trỏ bridge 127.0.0.1:18083. Bridge chỉ proxy tới runner Java 127.0.0.1:18082, reads committed response trước, rồi giữ delivery POST thêm 14s. Không mock 201 hoặc sửa Java commit behavior.

Kết quả lần này:

1. POST checkout qua BFF → bridge → Java transaction commit thật.
2. BFF nhận **chưa có response headers**, hết budget trả **504 sau 12,03s**, code BACKEND_TIMEOUT/no-store. Đây là measured local sample, không production SLA.
3. GET `/api/orders/checkout` với original guest/key → 200, cùng order number và SHA-256 tracking capability digest mà bridge quan sát sau real commit. Không lưu/log raw token/capability/key/customer response.
4. Bridge quan sát **một POST**; SQL disposable DB trả **một customer_order + một ORDER_PLACED movement**. Không có blind POST retry để lấy kết quả.
5. Runner dọn owned bridge/frontend/Java và PostgreSQL/Redis containers. Normal suite cũng qua: CSV/body limits/cache stock, failed import restart, auth/CSRF/logout, lost-response reload/close/late 410 và READY/STARTED recovery. STARTED proof vẫn dùng explicit expiry injection/test-query termination như phase trước.

### Verification

| Lệnh | Kết quả / artifacts ignored |
| --- | --- |
| `npm run verify` | Exit 0, **52 frontend tests**, lint/typecheck/production build; `bff-timeout-frontend-verify.log` |
| `npm run build:worker` | Exit 0; `bff-timeout-worker-build.log` |
| Wrangler deploy `--dry-run` | Exit 0, không upload/deploy; `bff-timeout-worker-dry-run.log` |
| `pwsh -NoProfile -File tools/verify-operator-session.ps1` | Exit 0, real fault bridge proof ở trên; `bff-timeout-real-browser-verify.log`, `operator-browser/deadline-*.log` |
| Lint/typecheck sau generated artifacts | Exit 0; `bff-generated-artifact-lint-green.log`, `bff-timeout-typecheck.log` |
| `npm audit --json` | Exit 0, zero findings tại thời điểm kiểm tra; `bff-timeout-audit.json` |
| Python/PowerShell AST, git diff check | Qua |

Java không đổi nên không chạy lại Maven: **93 backend tests** là report phase checkout trước, không được tính là run mới của phase này. Real deadline test dùng Node BFF, không phải production Cloudflare/network packet chaos; Worker bundle/dry-run qua nhưng chưa có Worker → Java deadline runtime proof của phase này. Remaining: Java create lock/pool/query bounds + sanitized backend failures, ingress/concurrency, full correlation, larger-data pagination và backlog hai repo còn lại. Không claim toàn API-1 hoặc toàn bộ ba repo đã hoàn tất.

## API-1 follow-up — Java checkout lock / statement / connection waiting

Phase này bổ sung Java wait budgets và sanitized data-access errors, cập nhật khoảng trống của phase BFF phía trên. Không đổi schema, không provision secret, không push/deploy.

### Cơ chế và phạm vi

- `OrderService.create/recover/closeUnused` dùng Spring transaction timeout **10s**, gọi shared `configureWaitLimits()` đặt PostgreSQL **SET LOCAL lock_timeout=3s, statement_timeout=8s**. Settings thuộc transaction, không đổi global DB defaults. Advisory-key lock, stock lock và các JDBC statement trong checkout chịu policy này.
- `application.yml`: Hikari **connection-timeout=2000ms**, **validation-timeout=1000ms**. pgJDBC datasource properties: **connectTimeout=3s, loginTimeout=3s, socketTimeout=15s, cancelSignalTimeout=2s**, TCP keepalive bật. Đây là shared datasource configuration, không chỉ checkout: startup/Flyway/imports cũng bị ảnh hưởng và đã được kiểm tra hồi quy cục bộ.
- `ApiExceptionHandler`: Spring DataAccessException/TransactionException → **503 DATABASE_UNAVAILABLE**, message cố định nhắc outcome có thể chưa rõ/giữ key; không trả SQL, connection string, password hay exception details. Exception translation chạy sau service transaction boundary; không catch rồi tiếp tục query trong aborted transaction. `closeUnused` vẫn giữ contract 409 khi CannotAcquireLockException theo controller cũ. Qua BFF, backend 5xx tiếp tục thành generic 503 BACKEND_UNAVAILABLE.
- Tài liệu đối chiếu: [PostgreSQL 17 timeout settings](https://www.postgresql.org/docs/17/runtime-config-client.html), [Spring JDBC transaction timeouts](https://docs.spring.io/spring-framework/reference/data-access/jdbc/connections.html), [Hikari configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby), [pgJDBC connection/socket/cancel settings](https://jdbc.postgresql.org/documentation/use/). Spring timeout được áp dụng qua JDBC statements/time remaining; driver socket/cancel và acquisition kiểm soát những waits khác. Không cộng các số thành latency SLA.

### Tests và trước/sau

| Test | Trước sửa | Sau sửa / artifact |
| --- | --- | --- |
| `checkoutStockLockWaitIsBoundedAndTheSameKeyCanBeUsedAfterRollback` | Giữ real stock row lock; future vẫn chưa xong sau 6s, TimeoutException | 503 sanitized trước test bound; rollback không order/stock loss, release lock rồi retry cùng key tạo một order. `checkout-lock-wait-red.log` / `-green.log` |
| `anExhaustedConnectionPoolReturnsPromptlyWithoutStartingAnotherCheckout` | Mượn hết actual application pool; request chưa xong sau 5s | Acquire budget 2s → 503, không JDBC URL/password, release connections rồi same-key create đúng. `checkout-pool-wait-red.log` / `-green.log` |
| `jdbcConnectionAndSocketWaitingHaveExplicitConfigurationBudgets` | Effective Hikari datasource properties thiếu explicit driver budgets (`null`) | Assert bound properties và pool/validation settings. `checkout-driver-budgets-red.log` / `-green.log`. **Configuration proof**, không network outage/packet-loss proof |
| `checkoutKeyLockAlsoTimesOutWithoutExecutingASecondSale` | Coverage sau khi policy tồn tại, không giả tạo red | Giữ real physical guest/key advisory lock, 503 trước 6s, zero orders, release rồi same-key create; full verify |
| `checkoutStatementTimeoutRollsBackEarlierWritesWhenAuditStalls` | Coverage sau khi policy tồn tại | QA trigger giữ late audit 30s, API 503 trước test bound 13s; order/movements rollback và stock giữ nguyên; bỏ trigger rồi same-key create đúng; full verify |

Test late audit chỉ chứng minh bounded failure/rollback tại statement và retry, không khẳng định mọi code trong transaction bị hard-kill đúng giây 10. Cách inject hai sleep ngắn ban đầu qua khi chạy riêng nhưng full suite có lần trả 201 (test duration ~9,7s); vì vậy không dùng nó làm proof tổng 10s hay suy đoán root cause clock/runtime. Đổi fault injection sang **một late-audit stall 30s** để bắt buộc vượt statement budget, giữ nguyên expected 503, zero order/movement, stock và same-key retry assertions; đặt tên test đúng phạm vi thực sự được chứng minh.

Full suite ban đầu còn fail test staging cũ vì kỳ vọng exception leak khỏi MockMvc. Hợp đồng API nay chủ động sanitize DB failures, nên test staging được đổi sang 503 DATABASE_UNAVAILABLE; **giữ toàn bộ assertions migration_job/import_work_item/staged_import_row rollback**. Không bỏ kiểm tra dữ liệu hoặc ignore failure.

### Verification mới

| Lệnh | Kết quả | Local ignored artifact |
| --- | --- | --- |
| Trong backend: `.\mvnw.cmd -q spotless:apply verify` | Exit 0, **98 tests**, 0 failures/errors: 90 PostgreSQL/Redis integration + 8 crypto/body/validation/config focused tests | `backend/target/checkout-wait-bounds-verify.log`, Surefire XML |
| `npm run verify` | Exit 0, **52 frontend tests**, lint/typecheck/Next production build | `backend/target/checkout-wait-bounds-frontend-verify.log` |
| `pwsh -NoProfile -File tools/verify-operator-session.ps1` | Exit 0 với **JAR mới** và disposable DB/Redis; normal browser suite + delayed-response bridge | `backend/target/checkout-wait-bounds-browser-verify.log`, `operator-browser/` |
| Compose config / Helm lint / git diff check / QA Python AST | Qua; Helm advisory icon recommendation only | Không in secret config |

Real browser chạy lại auth/CSRF/logout, strict encoding/body budgets, warm-cache current stock, async FAILED import/restart, READY/STARTED process recovery, guest lost-response/reload/closure/late 410. BFF delayed-delivery proof với JAR mới: Java commit → BFF **504 sau 12,03s** → GET original guest/key cùng order/token digest, **một POST/order/stock movement**. Đây là response-delivery fault injection sau commit; không phải DB network partition hay natural lease recovery SLA. STARTED recovery vẫn có explicit expiry injection/test-only orphan query termination như trước.

### Giới hạn / triển khai

- Không coi 503 là bằng chứng rollback trong mọi production failure: commit acknowledgement có thể mất. Giữ original key, GET recovery; không auto retry bằng key mới. Controlled lock/pool/audit tests chứng minh rollback của các fault đó, không phải tất cả commit windows.
- Spring timeout/JDBC statement limits không hard-cancel arbitrary Java computation, mọi commit/network/OS/DNS wait hay toàn servlet chain. Driver socket deadline 15s có thể dài hơn BFF 12s; BFF abort không hủy transaction. Không tuyên bố Java luôn hoàn tất trước BFF.
- Connection URL và deployment overrides có thể đổi effective driver settings. Các giá trị này cần smoke/tune trên hosting thực, nhất là Neon cold-start/network latency và long Flyway maintenance. Shared socket budget không là fully bounded total import workflow, heartbeat hay availability SLA.
- Chưa thêm network blackhole/commit-ack-loss chaos tests; driver property test chỉ là config binding. Chưa cover đầy đủ Redis/OTLP/auth/ingress waits, aggregate concurrency/buffer memory, pagination hay failure correlation. Worker → Java production/runtime timeout smoke vẫn thuộc rollout gate.
- Đã dọn owned QA processes/containers sau runner. Chưa remote CI, commit/push/deploy. Backlog tiếp theo: V-Pulse operation identity/recovery và V-Core convergence/idempotency theo overall plan; không claim ba repo hoàn tất hoặc zero technical debt.
