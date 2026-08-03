# V-Market Security Notes

## Order Data

`POST /api/orders` validates and normalizes cart and checkout payloads before persistence:

- Product IDs must exist in the server catalog.
- Quantities must be finite integers within product inventory and the platform quantity cap.
- Customer text fields are trimmed, length-limited, and never used for pricing.
- Email, delivery window, payment method, and privacy acknowledgement are required.
- Totals are calculated server-side from trusted catalog data.

Order JSON is persisted to the Cloudflare R2 bucket bound as `V_MARKET_ORDERS`.

## Payment Scope

The current storefront does not claim card authorization or settlement. Supported order-capture methods are:

- `invoice`: invoice before dispatch.
- `cod`: pay on delivery.

Both methods create orders with `paymentStatus: "pending"`.

## Dependency Audit

Run:

```bash
npm audit --audit-level=high
```

The current lockfile returns `found 0 vulnerabilities`.

The project uses npm `overrides` to keep Next.js and OpenNext on the required stack while forcing patched or non-advisory transitive versions of `postcss`, `sharp`, and `@node-minify`. The lint stack uses ESLint flat config with `typescript-eslint` directly to avoid vulnerable legacy minimatch chains from framework lint presets. If a future audit reports a framework transitive finding, do not apply `npm audit fix --force` blindly because it can replace core framework packages. Prefer a compatible package upgrade or a narrow override with a full `test/lint/build/opennext` pass.
