# V-Market Pain Point Proof

This document maps the two required e-commerce pain points to implementation and verification evidence.

## Pain Point 3: LCP And Media Bottlenecks On 3G/4G

Implementation evidence:

- `next.config.ts` enables `image/avif` and `image/webp` output and constrains `deviceSizes`/`imageSizes` for responsive delivery.
- Product and social images are stored locally under `public/products` and `public/social`, removing runtime dependency on third-party catalog image URLs.
- `components/hero-section.tsx` marks the first-viewport hero image with `priority`, explicit `width`/`height`, and responsive `sizes`.
- `components/product-card.tsx` renders every product image through `next/image` with explicit source dimensions, a stable `aspect-[4/5]` wrapper, and per-viewport `sizes`.
- `public/_headers` applies immutable caching to Next static, product, and social assets for Cloudflare.
- `wrangler.jsonc` defines a Cloudflare Images binding named `IMAGES` for OpenNext image optimization on Workers.

Verification evidence:

- `npm run build` verifies the route is statically prerendered where possible and compiles the optimized image usage.
- `tools/qa_playwright.py` asserts the rendered hero image has `srcset`, the expected responsive `sizes`, and a priority preload link.
- `tools/qa_playwright.py` scrolls through the rendered catalog and fails if any image has zero natural dimensions.
- `tools/qa_playwright.py` captures desktop, 390px mobile, and 320px mobile screenshots to verify the layout does not overflow or overlap.
- The same QA script passed against the Cloudflare Workers deployment at `https://v-market.vmarket-vietdung2005.workers.dev`.

## Pain Point 4: API Race Conditions In Cart Operations

Implementation evidence:

- `lib/cart-race.ts` owns per-product `AbortController` cancellation and sequence checks.
- `hooks/use-cart.ts` applies optimistic cart updates through TanStack Query `onMutate`.
- `hooks/use-cart.ts` ignores aborted request errors and only accepts success/rollback payloads when the mutation sequence is current.
- `lib/cart-storage.ts` persists the client cart safely, so refreshes do not drop shopper intent.
- `app/api/cart/quantity/route.ts` supports cancellable request handling and returns `Cache-Control: no-store`.

Verification evidence:

- `lib/cart-race.test.ts` proves a second quantity mutation aborts the first request and marks the first sequence stale.
- `lib/cart-race.test.ts` proves quantity writes are normalized and deterministic.
- `tools/qa_playwright.py` rapidly clicks the first product quantity control and waits for the cart count to settle at the newest requested value.
- The same QA script completed checkout on the live Cloudflare Workers deployment.

## Production Readiness Notes

V-Market now includes a complete storefront slice:

- Search, category filtering, and sorting.
- Persistent cart with quantity controls.
- Checkout form with server-side payload normalization, privacy acknowledgement, explicit invoice/COD payment method, order reference generation, and Cloudflare R2 order JSON persistence.
- SEO metadata for Open Graph, Twitter Card, robots, and canonical URL.
- Cloudflare Workers/OpenNext deploy configuration.
- Cloudflare R2 order persistence via `V_MARKET_ORDERS`.
- GitHub Actions CI for unit tests, lint, and production build.

External production systems that remain outside the provided credentials:

- Real customer authentication.
- Card payment settlement is intentionally not simulated. Current payment methods are invoice before dispatch and pay on delivery, both represented as `paymentStatus: "pending"` orders.
- Customer identity accounts remain an external integration; guest order capture itself is validated and persisted in Cloudflare R2.

Observed production persistence proof:

- Live API rejected an unknown product payload with HTTP 400 and a validation error.
- Live API `POST /api/orders` returned order `VM-MS17Z0PH` with `storage: "r2"` and `paymentStatus: "pending"`.
- `wrangler r2 object get v-market-orders/orders/VM-MS17Z0PH.json --remote --pipe` returned the persisted order JSON.
