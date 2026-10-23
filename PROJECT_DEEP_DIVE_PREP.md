# V-Market — Ôn project cho Backend Intern/Fresher

Cập nhật ngày **18/10/2026**, đối chiếu code tại commit `ff9fde3`. Tài liệu này thay bản snapshot 17/10, không phải báo cáo deployment. Ưu tiên câu trả lời ngắn, hiểu được code và ví dụ; không cần chủ động trình bày mọi cơ chế recovery nâng cao.

## 1. Chỉ cần nhớ câu chuyện này

V-Market là web thương mại với catalog, guest checkout, theo dõi đơn và công cụ nhập CSV cho operator. Hai bài toán backend là giữ tồn kho đúng và xử lý dữ liệu nhập cũ có kiểm soát.

**Điểm trình bày chính:** nhiều người mua cùng sản phẩm hoặc gửi lại request sau mất response; hệ thống không được bán vượt tồn hoặc tạo thêm đơn cho cùng một thao tác.

- Frontend TypeScript, React/Next.js; Next BFF gọi Java 21/Spring Boot.
- PostgreSQL là nguồn dữ liệu nghiệp vụ; JDBC cho các thao tác DB, Spring Batch hỗ trợ import.
- Redis cache **metadata catalog**, không cache số tồn kho authoritative. Mỗi GET catalog đọc availability/eligibility từ DB; checkout vẫn khóa và kiểm tra lại.
- Guest checkout dùng capability ngẫu nhiên theo tab; đây không phải account khách hàng đã xác thực. Operator có session, role và CSRF riêng.
- Backend là một ứng dụng chia module/package, không cần gọi là microservices.
- Không trình bày thao tác tạo đơn như đã tích hợp cổng thanh toán thực hoặc đã xác nhận thanh toán bên ngoài.

### Bài giới thiệu tiếng Anh khoảng 40 giây

> V-Market is a commerce project built with Java, Spring Boot, PostgreSQL, Redis, and a React frontend. My main example is stock-safe checkout. The backend locks the required stock rows, checks availability, and saves the order and inventory changes in one transaction. It also recognizes repeated checkout operations so a lost response does not cause another order. A second feature imports legacy CSV files, including Japanese CP932 data, while keeping invalid rows visible for review.

Đây là mô tả project; khi nói phần bản thân làm, chỉ thêm đóng góp đã thực sự hiểu và thực hiện.

## 2. Luồng phải hiểu: checkout

1. Browser chuẩn bị operation key và guest capability; lưu metadata nhận diện thao tác trước khi gửi. Payload được chuẩn hóa và kiểm tra bằng schema.
2. Request qua BFF đến Java; Java validate dữ liệu, nhận diện scope của guest và key.
3. Trong transaction, khóa theo operation key rồi kiểm tra đơn đã tồn tại chưa. Cùng nội dung thì trả kết quả cũ trong cửa sổ replay; khác nội dung thì conflict.
4. Với đơn mới, khóa product/stock theo thứ tự SKU ổn định; kiểm tra tồn khả dụng và lấy giá từ DB, không tin giá do browser gửi.
5. Tạo order/items, trừ kho, tăng version kho, lưu inventory movement và audit trong cùng transaction.
6. Commit rồi trả order number, tracking token và tổng tiền. Nếu browser không nhận được response, giữ key để tra cứu kết quả; không tự tạo key mới.

**Ví dụ tự giải thích:** có 27 sản phẩm, hai khách cùng mua 20. Một request khóa và mua trước, tồn còn 7. Request sau đọc lại dưới lock, không đủ hàng nên bị từ chối. Kết quả đúng là một đơn thành công, không phải tồn âm.

Phân biệt hai khóa: khóa operation key ngăn xử lý lặp cùng thao tác; khóa stock bảo vệ tồn kho giữa các đơn khác nhau.

## 3. Tám câu hỏi cần học trước

### 1. Tại sao không chỉ SELECT tồn kho rồi UPDATE?

Hai request có thể cùng đọc tồn cũ trước khi bên kia commit. Repo khóa row trước khi kiểm tra và giữ khóa đến hết transaction; bên đến sau phải kiểm tra lại. Chỉ dùng Java `synchronized` không đủ khi chạy nhiều backend instance.

**English:** “The stock rows are locked before availability is checked. Another checkout must wait and then validate against the updated stock.”

### 2. Vì sao phải dùng transaction?

Order, items, thay đổi stock, movement và audit cần nhất quán. Nếu một lỗi làm transaction rollback thì không để lại đơn đã tạo mà thiếu lần trừ kho, hoặc đã trừ kho nhưng thiếu đơn. Không suy ra DB transaction bao phủ thanh toán/giao hàng bên ngoài.

**English:** “The order and inventory changes are committed together. A database failure should not leave a partially completed checkout.”

### 3. Retry có tạo đơn trùng không?

Backend nhận diện guest scope + key và so request fingerprint. Cùng thao tác/cùng nội dung trả lại đơn cũ trong cửa sổ replay; cùng key nhưng thay quantity/customer data bị conflict. Khóa theo key cũng xử lý hai duplicate đến đồng thời, không chỉ retry tuần tự.

**English:** “A repeated checkout with the same guest identity, key, and payload returns the existing result instead of consuming stock again.”

### 4. Nếu mất response, làm sao biết đã có đơn chưa?

Client giữ metadata thao tác trong tab và dùng GET recovery với guest capability/key để tìm đơn đã commit. HTTP timeout hoặc 404 chưa đủ để kết luận request cũ sẽ không đến sau. Không tự gửi một key mới để chữa trạng thái chưa rõ.

Repo có chức năng đóng một **checkout chưa có đơn**; việc đóng dùng cùng lock với create để chặn late request. Đây không phải hủy đơn hàng đã tồn tại. Mức Intern chỉ cần nắm nguyên tắc “tra cứu trước, không tạo lại mù quáng”.

### 5. Redis dùng để làm gì? Dữ liệu cache cũ có gây oversell không?

Redis cache tên/giá/thông tin catalog để giảm đọc metadata. Số tồn hiển thị được lấy từ DB, nhưng vẫn chỉ là snapshot, không phải giữ hàng cho khách. Khi mua, backend khóa và kiểm tra DB lại. Redis lỗi có thể fallback DB; metadata vẫn có giới hạn freshness do TTL.

**English:** “Redis helps catalog reads, but checkout does not trust cached availability or browser prices. It validates the database state under lock.”

### 6. CSV tiếng Nhật khó ở đâu, và import bảo vệ dữ liệu thế nào?

Repo nhận UTF-8 và CP932/Windows-31J với strict decoding, kiểm tra header, số cột, SKU và dữ liệu từng dòng. File lỗi cấu trúc/encoding có thể bị từ chối toàn bộ; lỗi nghiệp vụ từng row được lưu để review. Giới hạn hiện tại là 1.000 data rows và 5.000.000 bytes, không phải nền tảng import file vô hạn.

Feed legacy không có version chỉ cập nhật metadata. Muốn thay stock bằng snapshot v2 phải mang expected stock version; version được so sau khi lấy lock. Snapshot cũ không được khôi phục số hàng đã bán.

**English:** “Legacy files update metadata only. Stock snapshots need a matching version, checked under lock, so an old file cannot overwrite newer inventory.”

### 7. Import lỗi giữa chừng có phải chạy lại từ đầu không?

Import staging được lưu bền vững, sau đó xử lý theo nhóm tối đa 50 dòng trong transaction riêng. Nhóm đã commit được giữ; nhóm lỗi rollback. Restart job FAILED giữ job identity và bỏ qua row đã áp dụng, không tạo lại movement đã commit.

UI hiện gửi async preference, nhận 202 sau staging rồi poll job. Có worker ownership/recovery sau process interruption, nhưng phần này chỉ cần đọc sâu khi được hỏi; không hứa tự phục hồi được mọi crash window.

### 8. Em có bằng chứng gì cho những điều đó?

Repo có integration tests với PostgreSQL/Redis thật qua Testcontainers: concurrent checkout, simultaneous duplicate, snapshot cũ và import fail/restart. Assertion phải kiểm tra order count, stock và movement, không chỉ HTTP 200. Browser fixture và test DB là hai loại bằng chứng khác nhau.

**English:** “The integration tests verify both responses and database invariants, such as one order and one stock change for concurrent duplicate requests.”

## 4. Đọc code theo thứ tự, không đọc cả repo

| Bước | File | Đọc để trả lời |
| --- | --- | --- |
| 1 | [OrderController](backend/src/main/java/dev/vmarket/order/OrderController.java) | Request, key/capability và service checkout. |
| 2 | [OrderService](backend/src/main/java/dev/vmarket/order/OrderService.java) | `create`: key lock → fingerprint → stock lock → giá/tồn → order/movement/audit. |
| 3 | [CheckoutHandler](lib/checkout/handler.ts), [checkout spec](lib/checkout/spec.ts) | Vì sao lưu operation trước POST và giữ key khi response chưa rõ? |
| 4 | [CatalogQueryService](backend/src/main/java/dev/vmarket/catalog/CatalogQueryService.java) | Metadata cache khác stock đọc từ DB. |
| 5 | [Integration tests](backend/src/test/java/dev/vmarket/VMarketApplicationTest.java) | Đọc ba test checkout dưới đây. |
| Sau checkout | [LegacyImportService](backend/src/main/java/dev/vmarket/migration/LegacyImportService.java), [ImportChunkService](backend/src/main/java/dev/vmarket/migration/ImportChunkService.java) | Parse/stage khác apply; transaction theo nhóm; kiểm tra version stock. |
| Khi được hỏi | [ImportRecoveryService](backend/src/main/java/dev/vmarket/migration/ImportRecoveryService.java), [ImportWorkService](backend/src/main/java/dev/vmarket/migration/ImportWorkService.java) | Vì sao worker cũ không được ghi sau khi mất quyền xử lý? |
| Khi được hỏi | [ReconciliationService](backend/src/main/java/dev/vmarket/reconciliation/ReconciliationService.java) | Snapshot không còn so được khác corruption thế nào? |
| Khi được hỏi | [OperatorSessionController](backend/src/main/java/dev/vmarket/shared/OperatorSessionController.java), [SecurityConfiguration](backend/src/main/java/dev/vmarket/shared/SecurityConfiguration.java) | Session/role/CSRF cho ops; service secret không thay người dùng. |

Ba test checkout đáng hiểu nhất:

- `concurrentCheckoutCannotOversellInventory`: hai đơn khác nhau tranh tồn kho, chỉ một bên mua đủ.
- `simultaneousDuplicateCheckoutReturnsOneOrderAndConsumesStockOnce`: cùng thao tác đến đồng thời vẫn một đơn/một lần trừ kho.
- `rejectsReusingACheckoutKeyForDifferentQuantities`: key không được tái sử dụng cho nội dung khác.

Nếu có thời gian, đọc `anOldVersionedStockSnapshotCannotRestoreAlreadySoldInventory` và `aFailed120RowImportRestartsTheSameInstanceWithoutRepeatingCommittedMovements`. [Evidence](docs/evidence/remediation-2026-10-18.md) mô tả các lượt đã chạy và giới hạn; không dùng số test cố định làm câu học thuộc.

## 5. Câu hỏi tình huống: trả lời theo cách đơn giản

**Khách nói bấm mua nhưng không thấy kết quả?** Giữ operation key/capability, tra cứu trạng thái. Phân biệt mất response với giao dịch thất bại; không yêu cầu mua lại bằng key mới ngay.

**Catalog hiện còn hàng nhưng checkout báo hết?** Dữ liệu xem hàng là snapshot; người khác có thể mua trước. Quyết định cuối dựa trên tồn DB đã khóa, không dựa trên số từng hiển thị.

**Import xong, bán hàng rồi reconciliation thấy khác snapshot?** Thay đổi hợp lệ sau import không tự động là lỗi. Repo phân biệt changed-since-import và stock chưa được xác minh với discrepancy có thể so sánh; không tự sửa stock theo file cũ.

**Khách hàng gửi file CSV lỗi?** Hỏi encoding/schema và policy từ chối file hay quarantine row; lấy mẫu đã loại dữ liệu nhạy cảm, kiểm tra error report và acceptance criteria. Đây là cách tiếp cận tình huống, không phải incident khách hàng đã trải qua.

## 6. Chỉ đọc khi được hỏi sâu

- Replay response checkout có cửa sổ 24 giờ; hết hạn không xóa key để tự cho đặt lại. Tracking token ngẫu nhiên độc lập với key, bản lưu để replay được bảo vệ bằng encryption. Không cần mở đầu bằng thuật toán mã hóa.
- Guest capability là bearer secret, không phải danh tính khách hàng đã xác thực. Storage theo tab không phải recovery xuyên thiết bị và không thay biện pháp bảo vệ XSS.
- Import có claim token/generation/lease: hiểu đơn giản là quyền sở hữu lượt xử lý, để worker cũ không ghi đè worker mới. Unknown/mismatch hoặc quá ngưỡng recovery có thể BLOCKED, không retry vô hạn. Không cần thuộc mọi SQL metadata của Spring Batch.
- Có body/dependency wait budgets; BFF timeout không chứng minh Java đã rollback. File checksum hiện dedup raw bytes/global, không phải hiểu hai file cùng nội dung nghiệp vụ/vendor.
- Metadata cache vẫn có TTL/late-fill race. Không claim strong consistency của mọi field hoặc exactly-once toàn hệ thống.

## 7. Cách học trong khoảng 60–75 phút

1. **10 phút:** ví dụ 27 hàng, hai khách mua 20; tự giới thiệu project.
2. **20 phút:** trace `OrderService.create`, phân biệt transaction, operation key lock và stock lock.
3. **15 phút:** đọc ba test checkout và giải thích stock/order count cuối.
4. **15 phút:** hiểu CP932, staging/chunk, legacy metadata-only và snapshot version.
5. **10–15 phút:** đóng tài liệu, trả lời tám câu hỏi; chưa rõ chỗ nào thì quay lại đúng file đó.

Đây là project phù hợp để học sâu nếu muốn tập trung SQL/transaction và nghiệp vụ Backend Intern. Chưa cần chủ động kể AWS/Kubernetes, crypto internals hay mọi crash recovery window.

### Tự kiểm tra trước phỏng vấn

- [ ] Phân biệt khóa chống oversell và khóa chống duplicate checkout.
- [ ] Giải thích được giá từ DB, stock/movement/audit cùng transaction.
- [ ] Biết làm gì khi response bị mất, không đổi key tùy tiện.
- [ ] Biết CSV lỗi file khác row bị quarantine; snapshot cũ bị chặn vì sao.
- [ ] Nêu được một test, một giới hạn và một đóng góp thực sự hiểu/thực hiện.

Mẫu đóng góp, **chỉ điền việc đã thực sự làm**: “Em tập trung vào [checkout/import]. Em đã [đọc/viết/debug/thực hành cụ thể], và kiểm tra bằng [test cụ thể].” Có AI hỗ trợ thì mô tả đúng phạm vi khi được hỏi; không nhận toàn bộ kiến trúc hoặc kinh nghiệm production từ việc repo có code.
