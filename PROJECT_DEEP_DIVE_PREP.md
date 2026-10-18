# V-Market — Java Backend & FDE Interview Prep

Snapshot: **2026-10-17**, local commit `e31c7f2`. Mục tiêu Intern/Fresher/Junior. P0 của riêng project này được thiết kế cho một lượt đọc code + ôn khoảng 60–90 phút.

## Cập nhật remediation local — đọc trước phần baseline

Các phần đánh giá/đáp án bên dưới giữ nguyên snapshot 2026-10-17 để truy vết, **không phải mô tả hiện trạng sau sửa**. Working tree hiện đã thay đổi checkout idempotency, operator authentication và stock import. Xem [evidence local](docs/evidence/remediation-2026-10-18.md) cho lệnh kiểm tra và giới hạn; chưa deploy các thay đổi này.

- Checkout có advisory transaction lock theo key và canonical payload hash. Cập nhật MARKET-1: key được hash theo guest capability/operation scope; capability 32 random bytes được giữ theo tab, không là verified user. Đọc [OrderService](backend/src/main/java/dev/vmarket/order/OrderService.java) → [CheckoutTrackingTokens](backend/src/main/java/dev/vmarket/order/CheckoutTrackingTokens.java): random token 256-bit, DB lookup hash + AES-GCM replay copy gắn order ID; retry trả token cũ trong 24h theo DB clock, hết hạn/ciphertext lỗi → 409 không tạo lại đơn. [CheckoutReplayRetention](backend/src/main/java/dev/vmarket/order/CheckoutReplayRetention.java) dọn tối đa 100 ciphertext hết hạn/lượt, giữ order/key/hash. Historical raw key không được tự adopt; link tracking cũ vẫn hợp lệ. `CHECKOUT_REPLAY_KEY` phải stable/secret phía backend; chưa có rotation keyring. Unknown-result/reload operation UX và checkout waiting bounds vẫn mở; đừng nói toàn bộ MARKET-1 đã xong.
- Ops cần account BCrypt, JDBC session, role hiện tại trong DB và CSRF; BFF/ops secret không thay thế user authentication. Audit fulfillment lấy principal.
- Đọc [LegacyImportService](backend/src/main/java/dev/vmarket/migration/LegacyImportService.java) → [ImportChunkService](backend/src/main/java/dev/vmarket/migration/ImportChunkService.java): legacy CSV chỉ đổi tên/giá; schema v2 mang expected stock version từ [snapshot export](backend/src/main/java/dev/vmarket/migration/InventorySnapshotController.java). Compare version **sau khi lock**; stale → reject toàn row; duplicate normalized SKU → reject mọi occurrence. Không lấy version hiện tại tại upload để hợp thức hóa snapshot cũ.
- Quantity/version/movement reference và applied marker cùng transaction. Metrics import/cache invalidation sau commit; rollback không ghi nhận thành công. Không gọi metadata cache là strong consistency: TTL/late-fill race vẫn là giới hạn; tồn kho đã được tách khỏi Redis như phần cập nhật bên dưới.
- Cập nhật MARKET-4: [CatalogQueryService](backend/src/main/java/dev/vmarket/catalog/CatalogQueryService.java) chỉ cache metadata trong key v2; mỗi GET đọc stock/active eligibility từ DB nên checkout không cần xóa stock cache nữa. Giá/tên/list vẫn có TTL/late-fill race, checkout vẫn lấy giá thật dưới lock. [LegacyImportService](backend/src/main/java/dev/vmarket/migration/LegacyImportService.java) strict UTF-8/CP932, chặn duplicate header/wrong field count, tối đa 1.000 records/5.000.000 bytes. BFF và [RequestBodyLimitAdvice](backend/src/main/java/dev/vmarket/shared/RequestBodyLimitAdvice.java) có body budget độc lập; quá byte limit HTTP 413, lỗi CSV HTTP 400, encoding mâu thuẫn với file trùng HTTP 409. Checksum vẫn global/raw-byte, filename là label; không tự nhận semantic/vendor dedup hoặc giới hạn timeout toàn flow.
- [ReconciliationService](backend/src/main/java/dev/vmarket/reconciliation/ReconciliationService.java) so applied baseline, phân biệt mismatch với `changedSinceImportRows` và `stockNotVerifiedRows`; sale sau import không tự động thành corruption. Version tăng chỉ có nghĩa baseline đã thay đổi, không chứng minh mọi thay đổi đều hợp lệ.
- V5/V6 không gán baseline giả; V7 lưu work/claim token/generation/lease với fenced writes; V8 đánh dấu dispatch intent. UI gửi `Prefer: respond-async`, nhận 202 sau staging commit; [ImportDispatcher](backend/src/main/java/dev/vmarket/migration/ImportDispatcher.java) lấy từng async READY item qua job-row SKIP LOCKED rồi [ImportJobRunner](backend/src/main/java/dev/vmarket/migration/ImportJobRunner.java) chạy Batch. Client không gửi preference vẫn synchronous tương thích.
- Restart **caught FAILED execution** giữ jobId/JobInstance, bỏ qua row đã commit; completed replay không chạy lại, active/busy trả 409. UI polling recent jobs và restart FAILED; acknowledgement không đồng nghĩa apply thành công. Upload trùng đồng thời dùng transaction advisory lock theo checksum; dedup vẫn byte-based/global, không phải semantic/vendor dedup.
- READY work được giữ qua process restart. [ImportRecoveryService](backend/src/main/java/dev/vmarket/migration/ImportRecoveryService.java) hiện tự xử lý async CLAIMED hết lease: retire metadata phù hợp và revoke generation/requeue cùng transaction; pending-row selector bỏ qua movement đã commit. Đọc [ImportRepositoryFence](backend/src/main/java/dev/vmarket/migration/ImportRepositoryFence.java) → [ImportRepositoryConfiguration](backend/src/main/java/dev/vmarket/migration/ImportRepositoryConfiguration.java): metadata writer cũ cũng bị chặn, không chỉ business writes. Hoàn tất Batch trước khi release FINISHED; crash giữa hai bước chỉ ack, không launch lại. UNKNOWN/mismatch hoặc vượt ba lần recovery → BLOCKED. Primitive takeover riêng lẻ không đủ để recovery an toàn.
- Proof process STARTED: commit 50/51 rows → kill Java QA → terminate query sleep thử nghiệm và inject lease expiry trong DB disposable → restart → đúng 51 movements, hai executions/một JobInstance. Không gọi đây là đo SLA/natural lease expiry. Independent heartbeat, full timeout bounds, strict CSV decoding/limits và production rollout chưa hoàn tất; không trả lời “đã giải quyết tất cả” hoặc “exactly-once”.

English answer for current stock policy: “I use backend-exported stock versions as concurrency tokens. Legacy feeds update metadata only. Versioned stock rows are checked under the same product and stock locking protocol as checkout. A stale row is rejected without changing its price or stock. Reconciliation distinguishes comparable baselines, later changes, and unverified stock; zero mismatches is not a blanket verification claim.” Chỉ nhận là phần mình tự làm khi thực sự hiểu và có đóng góp tương ứng.

**Nhãn:** `Confirmed in code` = đọc được trong source/test; `Inferred` = suy luận từ implementation; `Not verified` = chưa xác minh. Test có trong source không đồng nghĩa đã chạy lại hoặc đang xanh. Lần này chỉ đọc code và viết tài liệu, không chạy ứng dụng, không gọi external service, không đọc `.env`. Path tính từ gốc repo; dòng theo snapshot.

**Đóng góp cá nhân: Not verified.** Code không chứng minh ai trực tiếp viết từng phần hoặc vì sao tác giả từng quyết định như vậy. Các đáp án mô tả hệ thống; chỉ nói “I built/implemented” cho phần bạn thực sự làm. Chưa hiểu thì học flow và thử giải thích, không học thuộc để nhận công lao chưa xác minh.

## 1. Project Overview

- **Confirmed in code:** Commerce backend có catalog, guest checkout, stock, token tracking, operations, CSV import và reconciliation [M1–M10].
- **Inferred:** Người dùng gồm khách mua hàng và operator đồng bộ catalog/tồn kho từ vendor cũ.
- Bài toán nổi bật: tránh oversell trong checkout và làm rõ dữ liệu lỗi khi nhập feed Nhật CP932 [M2,M4–M5].
- Next.js/React/TypeScript BFF, triển khai Cloudflare qua OpenNext; backend Java 21/Spring Boot [M11–M12].
- PostgreSQL là dữ liệu gốc; `JdbcClient` và SQL trực tiếp, **không dùng JPA/Hibernate cho các flow này** [M2,M5,M12].
- Redis cache catalog 5 phút, lỗi thì bỏ qua cache [M7].
- Spring Batch chạy một tasklet gọi các nhóm xử lý độc lập, không phải cấu hình `ItemReader/Processor/Writer` kiểu chunk-oriented [M5].
- CSV intake hỗ trợ UTF-8 và Windows-31J, staging, row rejection, checksum dedup, trạng thái/checkpoint [M4–M6].
- Guest checkout chỉ ghi payment method INVOICE/COD và trạng thái; chưa có tích hợp cổng thanh toán thực trong flow này [M1–M2,M6].
- **Not verified:** production traffic/performance, deployment đang hoạt động, user counts hoặc quyền tác giả. CI/container config là bằng chứng cấu hình [M12].

## 2. Architecture & Critical Flows

### Bản đồ bằng chứng

| ID | File / vị trí | Đọc để hiểu |
| --- | --- | --- |
| M1 | [OrderController.java:26](backend/src/main/java/dev/vmarket/order/OrderController.java#L26); [OrderModels.java:13](backend/src/main/java/dev/vmarket/order/OrderModels.java#L13) | Request, DTO validation, 201 response |
| M2 | [OrderService.java:31](backend/src/main/java/dev/vmarket/order/OrderService.java#L31), normalize:147, lock:167, replay:183 | Checkout, tiền, stock, transaction/idempotency |
| M3 | [VMarketApplicationTest.java:99](backend/src/test/java/dev/vmarket/VMarketApplicationTest.java#L99), retry:135, concurrency:147, tracking:178 | Assertions checkout/stock |
| M4 | [LegacyImportService.java:42](backend/src/main/java/dev/vmarket/migration/LegacyImportService.java#L42), staging:105, parse:146, charset:201 | CSV, checksum, staging, launch |
| M5 | [ImportBatchConfiguration.java:15](backend/src/main/java/dev/vmarket/migration/ImportBatchConfiguration.java#L15); [ImportChunkService.java:24](backend/src/main/java/dev/vmarket/migration/ImportChunkService.java#L24), product lock:99 | Tasklet, `REQUIRES_NEW`, per-row marker |
| M6 | [V1__commerce_baseline.sql:26](backend/src/main/resources/db/migration/V1__commerce_baseline.sql#L26), orders:44, migration:77; [V2__order_privacy_acknowledgement.sql](backend/src/main/resources/db/migration/V2__order_privacy_acknowledgement.sql) | Schema, constraints, order/item snapshots |
| M7 | [CatalogCache.java:14](backend/src/main/java/dev/vmarket/catalog/CatalogCache.java#L14); [CatalogQueryService.java:18](backend/src/main/java/dev/vmarket/catalog/CatalogQueryService.java#L18) | Cache-aside, TTL/fallback |
| M8 | [ReconciliationService.java:22](backend/src/main/java/dev/vmarket/reconciliation/ReconciliationService.java#L22) | Expected vs current stock/price |
| M9 | [MigrationController.java:26](backend/src/main/java/dev/vmarket/migration/MigrationController.java#L26); [backend-proxy.ts:16](lib/backend-proxy.ts#L16); [ops/imports/route.ts:3](app/api/ops/imports/route.ts#L3); [BffAuthenticationFilter.java:24](backend/src/main/java/dev/vmarket/shared/BffAuthenticationFilter.java#L24) | BFF/ops trust và public route |
| M10 | [OrderTrackingService.java:17](backend/src/main/java/dev/vmarket/order/OrderTrackingService.java#L17); [ApiExceptionHandler.java:21](backend/src/main/java/dev/vmarket/shared/ApiExceptionHandler.java#L21) | Token hash, response không chứa customer PII, error mapping |
| M11 | [order-api.ts:3](lib/order-api.ts#L3); [orders/route.ts:3](app/api/orders/route.ts#L3); [operations-api.ts:64](lib/operations-api.ts#L64) | Browser request → Next BFF |
| M12 | [backend/pom.xml:7](backend/pom.xml#L7); [VMarketApplication.java:10](backend/src/main/java/dev/vmarket/VMarketApplication.java#L10); [application.yml:11](backend/src/main/resources/application.yml#L11); [Dockerfile:1](backend/Dockerfile#L1); [ci.yml:58](.github/workflows/ci.yml#L58); [package.json](package.json) | Entry, dependencies, Batch config, delivery |
| M13 | [VMarketApplicationTest.java:263](backend/src/test/java/dev/vmarket/VMarketApplicationTest.java#L263), CP932:290, reconciliation:310 | Import/reconcile test evidence |

### Flow A — Checkout và chống oversell

Browser `submitOrder` gửi customer + SKU/quantity + key [M11] → `POST /api/orders` ở Next BFF → header server-side → Java controller `@Valid` [M1,M9] → `OrderService.create @Transactional` [M2] → tra key cũ → gộp SKU trùng → lock stock theo thứ tự SKU → kiểm tra available và lấy giá DB → tính tổng → insert order/items → giảm stock + inventory movement + audit → commit → 201 + order number/tracking token/totals.

Ví dụ test: tồn 27, hai request khác key cùng mua 20 → một 201, một 409, còn 7 [M3:147–171]. Request không chứa giá authoritative; tiền được tính ở server. Insufficient stock → 409, invalid order/DTO → 400 [M10]. Runtime failure trong transaction rollback các ghi DB; không suy ra DB commit chứng minh giao hàng/thanh toán bên ngoài thành công.

### Flow B — Nhập CSV CP932

`POST /api/ops/imports`, `text/csv`, source name + encoding [M9] → ops authorization → size check tối đa 5 MB → SHA-256 bytes → nếu checksum có rồi trả job cũ → decode Windows-31J/UTF-8 → Apache Commons CSV parse → validate từng dòng → staging transaction lưu job + cả dòng valid/reject [M4] → `JobLauncher` chạy tasklet [M5] → mỗi `applyNextChunk` lấy tối đa 50 dòng valid chưa applied, lock → kiểm tra SKU/vendor/reserved → update product/stock/movement/marker/checkpoint trong `REQUIRES_NEW` → invalidate cache → nhóm tiếp theo → trạng thái COMPLETED hoặc COMPLETED_WITH_ERRORS.

Failure boundary: nhóm đã commit tồn tại; nhóm lỗi rollback. Tuy nhiên **API upload trùng checksum chỉ trả job cũ, không launch lại job lỗi**. Có nền tảng lưu progress nhưng chưa có restart workflow đầy đủ qua API [M4:50–66,M9:26–46].

### Flow C — Reconciliation

`POST /api/ops/reconciliations` qua BFF → `ReconciliationService.reconcile` [M8] → kiểm tra job → tạo run → đọc staged rows valid đã applied và join product/stock hiện tại → so quantity/price → lưu discrepancies → complete run → trả report. Test sửa stock sau import rồi kiểm tra `QUANTITY_MISMATCH` [M13:310]. Đây là phát hiện chênh lệch hiện tại, **không tự sửa** và không tự xác định chênh lệch là lỗi: sale hợp lệ sau import cũng làm stock khác snapshot.

## 3. P0 Interview Q&A

### P0.1 — What is V-Market, and what part did you work on?

**Vietnamese explanation:** Kể một câu business rồi hai flow chính: checkout giữ stock đúng, nhập dữ liệu cũ có kiểm soát. FDE có thể hỏi cách xử lý dữ liệu vendor và acceptance criteria. Chưa xác minh đóng góp cá nhân nên phải tự chuẩn bị đúng phần đã làm.

**English sample answer:** “V-Market combines a commerce backend with a legacy catalog import flow. Customers can place and track orders, while operators can import vendor files and inspect discrepancies. Its main backend examples are stock-safe checkout and CP932 data intake. I would describe my own contribution only for the components I actually worked on.”

**Code evidence:** M1–M5,M8. **Possible follow-up:** “Why is importing old files a business problem?” **What I must understand:** sai encoding/sai SKU/sai stock gây dữ liệu khó tin; không gắn một khách hàng thật hoặc downtime giảm bao nhiêu nếu chưa có evidence.

### P0.2 — Walk me through checkout. Why should the server calculate the price?

**Vietnamese explanation:** Client gửi SKU và số lượng; server lấy `price_minor` từ DB, gộp SKU lặp và tính subtotal/shipping/tax. Nếu tin giá frontend, người dùng có thể sửa request. Giá trong order item là snapshot để order cũ không đổi theo catalog mới. Code hiện tính lại giá, không có bước so sánh với một “quoted price” client gửi.

**English sample answer:** “The client sends product identifiers and quantities. The service reads prices from the database and calculates the total on the server. It also merges repeated SKUs and stores the unit price in each order item. This prevents a modified browser request from choosing its own price.”

**Code evidence:** M11:14–28; M2:42–59,99–112; M6:67–74. **Possible follow-up:** “What if the price changed after the customer viewed the cart?” **What I must understand:** server-owned pricing ≠ báo giá cố định; cần xác định customer confirmation policy nếu muốn thêm. JPY ở đây dùng số nguyên, không nói mọi currency đều có hai chữ số thập phân.

### P0.3 — How do you prevent two orders from overselling the same stock?

**Vietnamese explanation:** `@Transactional` giữ lock từ `SELECT ... FOR UPDATE OF s` tới commit. Hai order cùng SKU phải chờ nhau và kiểm tra available sau lock. Chỉ thêm `@Transactional` mà không có lock/conditional write chưa tự tránh lost update/oversell. Sắp SKU khi lock giúp giảm deadlock cho checkout nhiều mặt hàng; không bảo đảm mọi flow khác cùng thứ tự.

**English sample answer:** “Checkout locks the stock rows before checking availability. The lock lasts until the transaction finishes. A second request must wait and then sees the updated stock. The integration test checks two purchases of twenty units against twenty-seven available units and expects one success and one conflict.”

**Code evidence:** M2:31,167–180,113–130; M3:147–171. **Possible follow-up:** “Why lock rows in a consistent order?” **What I must understand:** count/check-then-write race, deadlock cycle, database lock versus Java in-memory lock.

### P0.4 — What does duplicate-order protection guarantee in this implementation?

**Vietnamese explanation:** Gửi lại **sau khi commit** cùng key trả order cũ và không giảm stock thêm; unique key DB chống lưu hai order cùng key. Tuy nhiên không hash payload: cùng key khác hàng vẫn trả order cũ. Hai request cùng key đến trước commit đều có thể qua lookup; request sau có thể gặp unique violation thay vì nhận response cũ, do không lookup lại sau chờ stock lock. Test hiện có là retry tuần tự, concurrent oversell test dùng hai key khác nhau.

**English sample answer:** “A completed order can be retrieved by its idempotency key. The existing test shows that a sequential retry does not reduce stock twice. The database also has a unique constraint on the key. However, the code does not compare request payloads or fully handle simultaneous duplicate requests, so I would not claim complete retry semantics.”

**Code evidence:** M2:33–40,60,183–202; M6:44–48; M3:135–154. **Possible follow-up:** “What would you change for concurrent duplicates?” **What I must understand:** atomic claim/key+hash/stored response, error handling sau rollback, scope of key. Đó là cải thiện đề xuất, chưa triển khai. Browser helper mặc định tạo UUID mới mỗi lần gọi [M11:6], nên retry business operation phải giữ key cũ.

### P0.5 — How do you handle Japanese CSV files and invalid records?

**Vietnamese explanation:** Encoding phải được chỉ định; CP932/Shift_JIS/Windows-31J map sang Java Windows-31J. CSV parser xử lý cấu trúc, code kiểm tra header, length, số không âm. Dòng sai lưu staging kèm reason, không tự biến thành product hợp lệ. Canonical SKU/vendor được kiểm tra thêm khi apply; product mới chưa có bị reject. File được load/parse vào bộ nhớ, không phải streaming vô hạn.

**English sample answer:** “The upload declares its encoding, and CP932 input is decoded using Windows-31J. The importer validates headers and row values before applying data. Invalid rows stay in staging with an explanation. The apply step also checks the canonical SKU and vendor, so an unknown product is rejected rather than silently created.”

**Code evidence:** M4:43–59,146–207; M5 chunk:42–55; M13:263–306. **Possible follow-up:** “What happens if the encoding header is wrong?” **What I must understand:** bytes khác text; sai decoding có thể sinh ký tự thay thế. Code dùng `InputStreamReader` mặc định, chưa có strict malformed-input decoder; không hứa phát hiện mọi lỗi encoding.

### P0.6 — Is this a chunk-oriented Spring Batch job, and can it restart?

**Vietnamese explanation:** Thực tế là **tasklet + vòng while**, gọi service bean có `REQUIRES_NEW` cho nhóm tối đa 50. Marker `applied_at IS NULL` quyết định phần chưa làm; `checkpoint_row` lưu tiến độ nhưng không phải offset được query để resume. `REQUIRES_NEW` qua bean khác tạo transaction riêng nên nhóm trước không rollback theo nhóm sau. Dù primitive hỗ trợ tiếp tục, upload endpoint không tự relaunch job lỗi.

**English sample answer:** “The job uses a tasklet, not Spring Batch's reader-processor-writer chunk API. The tasklet calls a separate service that commits up to fifty staged rows in a new transaction. Applied-row markers keep committed work from being selected again. However, the current API returns an existing job for a duplicate file, so an explicit restart operation is still missing.”

**Code evidence:** M5 config:23–35, chunk:24–39,84–96; M4:50–66; M9 controller. **Possible follow-up:** “Why is `REQUIRES_NEW` on a separate bean?” **What I must understand:** Spring transaction proxy, self-invocation, partial commit, same JobInstance/jobId khi restart. Không nói đã có end-to-end crash/restart test.

### P0.7 — Why reconcile after an import?

**Vietnamese explanation:** Import “completed” chỉ nghĩa xử lý đã xong, không đảm bảo current DB vẫn bằng file. Reconcile so applied valid rows với DB hiện tại, lưu quantity/price mismatch/missing product. Ví dụ import 80 rồi DB còn 79 → mismatch. Nhưng 1 unit vừa bán là lý do hợp lệ, cần cutoff hoặc inventory ledger để phân loại trước khi sửa.

**English sample answer:** “Reconciliation compares applied source rows with the current catalog and stock. It records differences so an operator can investigate them. The existing test changes stock after import and checks that a quantity mismatch appears. A mismatch is not automatically an error because a legitimate order can also change stock.”

**Code evidence:** M8:39–82; M13:310–338. **Possible follow-up:** “Would you automatically overwrite the database to match the file?” **What I must understand:** snapshot/cutoff, stock movement ledger, không tự sửa từ discrepancy chưa rõ nguyên nhân.

### P0.8 — What happens if Redis fails or the catalog is stale?

**Vietnamese explanation:** Cache-aside: đọc Redis trước, miss/lỗi đọc PostgreSQL rồi write TTL 5 phút. Import gọi invalidate; checkout không invalidate catalog trong service đã đọc. Catalog có thể hiển thị stock cũ, nhưng checkout dùng DB/lock để quyết định. Invalidation import xảy ra trước transaction commit, nên còn cửa sổ repopulate cache bằng dữ liệu cũ.

**English sample answer:** “Redis is a disposable catalog cache. A cache failure falls back to PostgreSQL instead of blocking the request. Catalog availability can be stale, but checkout checks stock again under a database lock. I would improve invalidation after checkout and after committed imports if fresher display data were required.”

**Code evidence:** M7:27–55; catalog query:18–35; M2; M5 chunk:95. **Possible follow-up:** “Does deleting a cache key inside a transaction guarantee consistency?” **What I must understand:** cache không tham gia PostgreSQL transaction; after-commit invalidation/versioning là phương án cải thiện, chưa hiện có.

### P0.9 — How do BFF credentials differ from operator authorization?

**Vietnamese explanation:** BFF secret chứng minh request đi qua server được tin cậy. Ops secret kiểm tra thêm ở controller. Tuy nhiên Next `/api/ops/...` tự inject cả hai, và route đã đọc không kiểm tra danh tính/role người dùng. Secret giấu khỏi browser là tốt nhưng không thay thế login operator; public demo ops khác production permission system.

**English sample answer:** “The BFF keeps backend credentials on the server. Java checks a BFF header and an additional operations secret for imports. However, the current Next operations route injects that secret without checking a user role. A production operator console would need authentication and authorization at that boundary.”

**Code evidence:** M9 proxy:25–29,50–53; ops route:3–8; migration controller:28–34. **Possible follow-up:** “Can anyone call the public operations route?” **What I must understand:** code route tự nó không kiểm tra user; platform access protection **Not verified**, không khẳng định đã test khai thác live.

### P0.10 — Which tests support the project claims, and what would you test next?

**Vietnamese explanation:** MockMvc với Spring + PostgreSQL/Redis Testcontainers kiểm tra đúng SQL/locks/encoding integration. Test hiện có stock/totals, retry tuần tự, oversell, CP932, bad row, reconciliation. Chưa thấy test kill giữa nhóm rồi restart, concurrent same-key checkout, cache failure/freshness hay operator identity trong các test đã rà.

**English sample answer:** “The tests use real PostgreSQL and Redis containers with the Spring application. They cover totals, stock changes, sequential retries, concurrent checkout, CP932 import, and reconciliation. They show specific expected outcomes rather than production performance. I would next test concurrent duplicate requests and a failed import resumed after a committed chunk.”

**Code evidence:** M3:34–48,99–175; M13. **Possible follow-up:** “How would you inject a failure after the first chunk?” **What I must understand:** kiểm tra DB state trước/sau, giữ jobId, assertion no duplicate movement; đây là test plan chưa viết/chạy trong lần này.

## 4. P1 Follow-up Questions

| Question | Ý chính để trả lời | Code |
| --- | --- | --- |
| Why JDBC instead of JPA here? | SQL cho lock/upsert/staging thể hiện rõ; đổi lại manual mapping/query. Đó là trade-off quan sát được, không khẳng định lịch sử lựa chọn cá nhân. | M2,M5,M12 |
| Does the file checksum mean the import is semantically identical? | Hash bytes: khác newline/encoding sinh checksum khác; cùng bytes bị dedup dù source name khác. Cần làm rõ scope vendor/version. | M4:50–59,93–102 |
| Why not roll back the whole file if one row is invalid? | Staging giữ rejects, good rows vẫn apply; phù hợp partial-acceptance nếu customer đồng ý, không phải mọi nghiệp vụ đều nên vậy. | M4 staging; M5 |
| Can import overwrite stock after valid orders? | Có absolute `on_hand = :onHand`, lock chỉ serialize, không xác định snapshot cũ/mới. Cần cutoff/version/ledger policy. | M5 chunk:66–82 |
| Does `checkpoint_row` alone provide restart correctness? | Không; selector dựa valid + unapplied. Đừng nhầm progress display với resume algorithm. | M5 chunk:26–31,88–92 |
| Is order tracking authenticated? | Capability token + order number; DB chỉ lưu hash, response không PII. Token derive từ key, phải bảo vệ key và token; không phải account login. | M2:60–64; M10 tracking:18–47 |
| Can malformed JSON pass DTO validation? | `@Valid` không đồng nghĩa `@NotNull`: customer nullable trong DTO rồi service dereference; null nested object cần test/validation bổ sung. | M1 models:13–24; M2:67–85 |
| What does HTTP 201 mean for an import with rejects? | Resource/job được tạo, trạng thái body COMPLETED_WITH_ERRORS mới nói về rows. Không suy HTTP success là mọi row đúng. | M9:26–34; M13:277–280 |

## 5. FDE Solution Questions

Đây là tình huống mô phỏng, không phải câu chuyện khách hàng thật.

| Question | Hướng trả lời | Code nền |
| --- | --- | --- |
| A vendor sends garbled Japanese product names. What do you ask first? | Xin file mẫu đã loại dữ liệu nhạy cảm, xác nhận encoding/export tool/header/delimiter; so raw bytes và chuỗi decode; thêm fixture test. Không đoán “mọi Shift-JIS đều giống UTF-8”. | M4:146–207; M13:290 |
| The customer wants import without stopping sales. Is locking sufficient? | Hỏi stock snapshot vào thời điểm nào, có delta feed không, xử lý sale sau cutoff ra sao. Row lock chống concurrent write nhưng không giải quyết dữ liệu snapshot cũ. | M2 lock; M5 stock update; M8 |
| What is the smallest useful migration MVP? | Một format, canonical SKU/vendor, staging + reject reasons, apply nhóm nhỏ, report; thống nhất tiêu chí total/applied/rejected và restart trước rollout. | M4–M6 |
| The operator sees a quantity mismatch after import. How do you investigate? | So job/source/applied row, stock movements/order timestamps; kiểm tra có sale hợp lệ trước khi rollback/overwrite. Không xóa evidence. | M5 movement:74–83; M8; M6 |
| How would you explain a partially successful import to a customer? | “Rows that passed the checks were saved; these rows need correction.” Nêu counts/reasons/next action và khả năng sửa file; không nói re-upload same file sẽ tự resume hiện tại. | M4 lookup; M5 finish:130–136 |

## 6. Interview Risks & Knowledge Gaps

Đối chiếu hai bullet V-Market trong **`D:/CV/main.tex:183–184`**. Source cho thấy capability của repo; không chứng minh chữ “Built” là đóng góp cá nhân của bạn.

| Interviewer hỏi | Bằng chứng / phân loại | Cần học và cách trả lời trung thực |
| --- | --- | --- |
| CV: server-side price validation, duplicate-order protection, no oversell? | **Confirmed:** tính giá DB, lock/check stock và test. **Giới hạn:** sequential retry, không payload hash/concurrent replay completion [M2,M3]. | Nói “server-calculated prices; tested sequential idempotency and stock contention”. Không khẳng định đã xử lý mọi duplicate/race. |
| CV: “restartable Spring Batch importer”? | **Confirmed:** partial commits + unapplied marker; **Confirmed API gap:** same checksum chỉ return, không restart [M4,M5,M9]. **Not verified:** crash/restart runtime. | Nói “committed chunks preserve progress; the exposed restart workflow still needs completion.” Có thể đề xuất sửa CV sau, tài liệu này không sửa CV. |
| BFF có làm `/ops` private không? | **Confirmed code gap:** route inject ops secret mà không user check [M9]. **Not verified:** platform gate. | Học trust boundary; mô tả demo operator controls, đề xuất user role/session và audit identity. |
| Import có ngăn lost sales data không? | **Confirmed:** absolute stock update dưới lock; **Inferred risk:** stale vendor snapshot overwrite sale đã commit [M5]. | Cần cutoff/delta/ownership policy; “no oversell during checkout” khác “import business consistency with sales”. |
| Redis freshness có bảo đảm không? | **Confirmed:** checkout không invalidate, import invalidate trước commit [M2,M5,M7]. **Inferred:** stale stock UI/cache race. | DB checkout vẫn kiểm tra stock; đề xuất after-commit invalidation, không nói strong cache consistency. |
| Import mọi file 5 MB có bền vững/performance cao? | **Confirmed:** parse toàn bộ vào list, max bytes 5 MB [M4]. **Not verified:** memory/load numbers, malformed-byte strict rejection. | Học decoding, memory, parser failures; không gọi streaming pipeline quy mô lớn. |
| Payment và tracking có production-grade security chưa? | **Confirmed:** COD/INVOICE, token derive từ idempotency key, hash token trong DB [M1,M2,M10]. | Không có real payment integration trong flow; token/key là bearer-sensitive, không phải encryption. |
| Framework nào tự viết? | **Confirmed:** Spring Batch orchestration, Commons CSV parsing, JDBC transaction infrastructure do library; policy/staging/SQL trong project [M4,M5,M12]. | Phân biệt sử dụng framework với tự xây framework; chưa có dữ liệu xác minh personal decisions. |

## 7. English Project Presentation

### A. 60 seconds

“V-Market is a commerce backend with a legacy data import workflow. It uses Java 21, Spring Boot, PostgreSQL, Redis, and Spring Batch, with a Next.js BFF on Cloudflare. The main business problems are stock-safe checkout and importing Japanese vendor files with visible validation results.

Checkout calculates prices on the server and locks stock rows before reducing inventory. The importer decodes CP932 files, stages rows, and commits valid data in small groups. Reconciliation makes later differences visible. Integration tests cover concurrent checkout and Japanese text decoding. The restart workflow and production operator authentication are areas that still need improvement.”

**Contribution sentence, tự điền đúng sự thật:** “My contribution was [component/change], and I verified [behavior] using [test I actually ran or wrote].” Không có bằng chứng để điền thay bạn.

### B. Technical deep dive — khoảng 3 phút khi pause và chỉ code

“The browser talks to a Next.js BFF, which forwards requests to Spring Boot. PostgreSQL holds the canonical catalog, orders, stock, and import state. Redis caches catalog reads, so it is not responsible for stock correctness.

For checkout, the client sends SKUs and quantities. The service merges repeated SKUs and reads prices from the database. It locks stock rows in SKU order inside one transaction. If two customers each buy twenty units when only twenty-seven are available, the first request succeeds and the second sees only seven units after waiting for the lock. The integration test checks those results and the remaining stock.

The order, item snapshots, stock changes, movement records, and audit event are saved together. A completed order can be returned again using the same idempotency key. I would be precise about the limitation: the implementation does not compare request payloads or fully replay simultaneous duplicate requests.

The other important flow is legacy import. The operator supplies a CSV file and encoding. The service computes a checksum, decodes CP932 using Windows-31J, validates the rows, and stores them in staging. A Spring Batch tasklet calls a separate service that applies up to fifty rows in a new transaction. It checks canonical SKU and vendor data and records invalid rows instead of silently accepting them.

Committed groups remain saved after a later failure. Applied markers identify work that should not be selected again. However, a duplicate upload currently returns the old job, so I would not claim that the public API provides a complete restart workflow.

After import, reconciliation compares staged expectations with current stock and price. A mismatch needs investigation because a valid sale may have changed stock. Before using this with a customer, I would agree on snapshot cutoffs, partial acceptance, restart behavior, and operator permissions. The existing tests demonstrate specific correctness scenarios; they do not establish production scale or business impact.”

## 8. Technical English Vocabulary

| Phrase | Nghĩa | Interview sentence |
| --- | --- | --- |
| server-owned pricing | server quyết định giá | “Checkout uses server-owned pricing.” |
| available inventory | tồn có thể bán | “Available inventory is on-hand stock minus reservations.” |
| oversell | bán quá tồn khả dụng | “The stock lock prevents overselling in the checkout flow.” |
| deterministic lock order | thứ tự khóa nhất quán | “Checkout uses a deterministic lock order by SKU.” |
| price snapshot | giá lưu tại lúc đặt hàng | “Order items keep a price snapshot.” |
| sequential retry | retry sau request trước hoàn tất | “The test covers a sequential retry.” |
| character encoding | cách bytes biểu diễn ký tự | “The upload declares its character encoding.” |
| staging table | bảng dữ liệu chờ xử lý | “Rows are validated in a staging table.” |
| quarantine invalid rows | giữ riêng dòng sai | “The importer quarantines invalid rows with reasons.” |
| checksum deduplication | nhận file trùng qua hash bytes | “Checksum deduplication detects the same file bytes.” |
| partial commit | commit từng phần | “A later failure does not undo earlier partial commits.” |
| applied-row marker | dấu dòng đã áp dụng | “The selector checks the applied-row marker.” |
| reconciliation discrepancy | chênh lệch khi đối soát | “A sale may explain a reconciliation discrepancy.” |
| snapshot cutoff | mốc thời gian của dữ liệu chụp | “We need a snapshot cutoff before replacing stock.” |
| after-commit invalidation | xóa cache sau khi commit | “After-commit invalidation would reduce this cache race.” |

## 9. Final Study Checklist

### Follow-up: database bị nghẽn thì checkout có treo mãi không?

Đọc `OrderService.configureWaitLimits()` và `@Transactional(timeout=10)` → `application.yml` phần Hikari/pgJDBC → `ApiExceptionHandler.databaseUnavailable()` → các test lock/pool/slow audit trong `VMarketApplicationTest`. Phân biệt ba loại chờ: lấy connection từ pool (2s), chờ DB lock (3s), chạy statement (8s). Spring transaction 10s giới hạn các JDBC statement theo thời gian còn lại, không là nút hủy mọi code/network/commit.

Pool/driver defaults dùng chung toàn datasource: validation 1s; connect/login 3s; socket read 15s; cancel channel 2s. Đây không phải “cộng các số là ra SLA” hoặc “chắc chắn Java xong trước BFF 12s”. Driver/network/config override và commit acknowledgement có thể làm kết quả chưa rõ. Trả 503 không có nghĩa được phép đổi key; phải recover cùng identity.

Test thật giữ stock/advisory locks, mượn hết connection pool, và treo audit sau các write trước đó. Sau lỗi: không order/movement mồ côi, tồn kho giữ nguyên; bỏ fault rồi retry cùng key mới tạo đúng một đơn. Test driver properties chỉ chứng minh config binding, không giả làm bằng chứng packet-loss/network chaos.

### Follow-up: response mất rồi reload thì sao? (local remediation)

Đọc `lib/checkout/spec.ts` → `handler.ts` → `hooks/use-checkout.ts` → `components/storefront.tsx` → `OrderController`/`OrderService` → V13. Trước POST, client lưu UUID + hash canonical payload/capability, không lưu customer hay tracking token. Reload chỉ restore, không tự POST. GET cùng guest/key trả committed response; 404 chỉ là chưa quan sát được, không chứng minh request cũ đã thất bại.

Muốn sửa thông tin khi chưa có order: POST close-unused dùng cùng advisory lock với create, lưu tombstone hash rồi trả 204. Request cũ đến sau bị 410; nếu order đã tồn tại thì close 409. Client chỉ mở checkout mới sau explicit acknowledgement của confirmed/closed state. Giới hạn 10.000 closure records chống tăng DB vô hạn nhưng chưa chống DoS; không xóa dấu đóng để nhường chỗ. Browser timeout 15s không hủy backend work. Không gọi cơ chế này là exactly-once hay khôi phục tài khoản xuyên thiết bị.

Bằng chứng mạnh: runner thật để backend commit, abort browser delivery, reload rồi GET cùng order/token, SQL một movement; một scenario khác giữ original request trước BFF, close rồi gửi late POST bị 410. Phân biệt transport fault injection này với packet-level network chaos và với unit mocks.

- [ ] 0–10 phút: overview + Flow A, chỉ được controller/service/schema trên màn hình.
- [ ] 10–30 phút: tự trace tổng tiền và hai order mua 20 trên stock 27; đọc test assertions.
- [ ] 30–40 phút: phân biệt sequential retry, concurrent duplicate và payload mismatch.
- [ ] 40–60 phút: decode → stage → tasklet → `REQUIRES_NEW` → markers; giải thích restart gap.
- [ ] 60–70 phút: reconciliation, stale cache và BFF/operator boundary.
- [ ] 70–90 phút: luyện 60 giây, 3 phút, một câu FDE và đóng góp cá nhân có thật.
- [ ] Đọc test CP932 để biết input bytes được tạo như thế nào và assert text Nhật ở đâu.
- [ ] Không học JPA/microservices/AWS sâu để giải thích flow JDBC này; delivery chỉ cần biết entry, Docker, Flyway, CI [M12].

| Priority | Topic | Why it matters | Source code | Estimated study effort |
| --- | --- | --- | --- | --- |
| P0 | Checkout, pricing, lock và transaction | Backend story dễ giải thích, trực tiếp trên CV | M1–M3,M6 | 25 phút |
| P0 | Idempotency guarantee và giới hạn | Tránh trả lời quá test/source | M2:37–40,183; M3:135 | 10 phút |
| P0 | CP932 staging + tasklet + group commits | Điểm khác biệt cho Java/FDE, dễ bị hỏi restart | M4–M5,M13 | 20 phút |
| P0 | Reconcile + cache + operator boundary | Nối technical flow với rủi ro business | M7–M10 | 15 phút |
| P0 | Tests + honest speaking | Chứng minh hiểu thay vì học thuộc | M3,M13; mục 7 | 20 phút |
| P1 | Tracking, parser edge cases, Docker/CI | Follow-up khi đủ thời gian | M4,M10,M12 | 20–30 phút thêm |
