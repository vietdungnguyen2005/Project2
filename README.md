# V-Market

V-Market is a performance-first multi-vendor commerce shell built for mobile catalog traffic and race-safe cart operations.

Live URL: https://v-market.vmarket-vietdung2005.workers.dev

## Architecture

- **Framework:** Next.js App Router with strict TypeScript.
- **Server state:** TanStack Query owns cart cache state, optimistic updates, query cancellation, and mutation settlement.
- **Styling:** Tailwind CSS v4 through `@tailwindcss/postcss`, with responsive grids from 320px mobile to wide desktop.
- **Media delivery:** Next Image is configured for AVIF/WebP output, responsive device sizes, explicit dimensions, and long-lived optimized image caching.
- **SEO:** Global App Router metadata covers canonical URL, robots, Open Graph, and Twitter summary card tags.
- **Deployment:** Cloudflare Workers via the OpenNext Cloudflare adapter.

## Performance Decisions

- The first-viewport hero image uses `priority`, explicit source dimensions, and responsive `sizes` so it becomes the deliberate LCP candidate.
- Product cards use fixed aspect-ratio media wrappers plus image `width` and `height`, preventing catalog layout shifts while images stream over slower networks.
- Product and social images are stored in `public/products` and `public/social`, so runtime catalog delivery does not depend on third-party image availability.
- The catalog relies on Next Image-generated AVIF/WebP `srcset` output instead of shipping one oversized image to every viewport.
- System fonts are used to avoid server-side font fetching and reduce cold-start render risk.
- The cart API route uses `Cache-Control: no-store`, while client state is cached and reconciled by TanStack Query.
- Shopper flow includes search, category filters, sort modes, persistent cart state, checkout capture, server-normalized order validation, and order reference confirmation.

## Cart Race-Control Model

Rapid quantity changes are coordinated per product:

1. Preparing a new quantity mutation aborts the previous in-flight request for that product.
2. TanStack Query applies the new quantity optimistically before the network round trip completes.
3. Every mutation receives a sequence number.
4. Success and rollback handlers update cached cart state only when their sequence is still current.
5. Aborted request errors are ignored, so stale backend payloads cannot overwrite the latest shopper intent.

## Verification

Run the local gates:

```bash
npm run test
npm run lint
npm run build
npm audit --audit-level=high
npx opennextjs-cloudflare build
```

Browser QA can be run through the package script once the app is serving:

```bash
npm run test:e2e
```

For rendered browser QA:

```bash
python C:\Users\steve\.codex\skills\webapp-testing\scripts\with_server.py --server "npm run start -- --hostname 127.0.0.1" --port 3000 --timeout 60 -- python tools\qa_playwright.py
```

Against a deployed URL:

```powershell
$env:BASE_URL="https://v-market.vmarket-vietdung2005.workers.dev"; python tools\qa_playwright.py
```

Cloudflare deploy helper:

```powershell
powershell -ExecutionPolicy Bypass -File tools\deploy-cloudflare.ps1
```

See `docs/PAIN_POINT_PROOF.md` and `docs/CLOUDFLARE_DEPLOYMENT.md` for requirement-level evidence.
