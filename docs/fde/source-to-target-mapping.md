# Source-to-target mapping

The migration seeds fictional `supplier-a` v1 (CP932, vendor NAMI) and `supplier-b` v1 (UTF-8, vendor RIVERBYTE). Both are active demo contracts, not real integrations. Multiple active versions are permitted; the operator must choose explicitly.

| Domain field | Supplier A source | Supplier B source | Built-in normalization / validation |
| --- | --- | --- | --- |
| `sku` | 商品コード | SKU | Required string, trim then uppercase using Locale.ROOT for seeded TRIM_UPPER; 1–64 characters; must exist and be active in the canonical catalog |
| `name` | 商品名 | ProductName | Required string, TRIM, 1–160 characters; the supplier-provided name is applied |
| `on_hand` | 在庫数 | AvailableQuantity | Required integer, trim/parse, 0–1,000,000 and not below current reservation |
| `vendor_code` | Not supplied | Not supplied | Existing canonical vendor must match the profile's bound vendor |
| `price_minor` | Not supplied | Not supplied | Preserve current canonical JPY price; supplier cannot supply another price through this contract |
| `expected_stock_version` | Not supplied | Not supplied | Capture current stock version at preview; recheck on confirmation and under stock lock on application |
| `expected_product_version` | Not supplied | Not supplied | Capture product version, preventing catalog enrichment from overwriting a later price/name update |

All three mapped fields are mandatory in every profile. Fixed type/range constraints are returned as `requiredFields`, `normalization`, and `validation` in the profile API; constraints are not arbitrary customer code. Custom profiles may choose `TRIM` for case-sensitive exact SKU matching instead of `TRIM_UPPER`. Name/stock rules remain fixed. Extra source columns are ignored; source headers must be distinct and row width consistent. No automatic encoding/BOM guessing or full-width character conversion is promised.

## Supported examples

Supplier A, shown as Unicode here; encode the file as CP932 before upload:

```csv
商品コード,商品名,在庫数
VM-001,軽量ジャケット,35
```

Supplier B UTF-8:

```csv
SKU,ProductName,AvailableQuantity
 vm-002 ,Modular desk,60
```

Use [supplier B sample](samples/supplier-b-mixed.csv). Run `python tools/generate_supplier_samples.py` to create an actual CP932 sample under `backend/target/supplier-samples/`; [supplier A source](samples/supplier-a-utf8-source.csv) itself is UTF-8, **not** a CP932 upload file.

## Rejected examples and operator action

| Input | Result | Action |
| --- | --- | --- |
| Missing `AvailableQuantity` | Whole-file 400; no staging | Match the chosen version's required source headers |
| CP932 bytes submitted to UTF-8 profile | Strict decode or required-header failure; no staging | Select the correct profile; upload original bytes |
| `AvailableQuantity=unknown`, negative, or above 1,000,000 | Row rejected; raw invalid value not included in error/report | Correct source data, preview again |
| Supplier B includes VM-001 (NAMI) | VENDOR_MISMATCH | Correct supplier ownership/mapping; no automatic catalog creation |
| Unknown SKU | UNKNOWN_SKU | Establish the canonical catalog separately; this feature does not create products |
| Two rows normalize to VM-002 | Every occurrence quarantined | Remove duplicates in source; do not choose an arbitrary winner |
| More than 1,000 rows / 5,000,000 bytes | Rejected before staging | Split intentionally; each resulting file has its own byte identity |
| Catalog changes after preview | Confirmation 409; no new job | Re-preview and review fresh enrichment |
| Stock/product changes after staging | Row rejected under lock | Inspect, reconcile already applied rows; prepare a newly reviewed source operation if appropriate |

Rejected text cells are not persisted in staging. Row number and allowlisted validation reason remain; accepted business fields are retained for application/proof. Preview displays at most 10 accepted mapped rows. Exported JSON contains counts and row-number/reason pairs, not file cells, names, filename or receipt.
