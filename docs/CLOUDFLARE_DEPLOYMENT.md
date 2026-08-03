# Cloudflare Deployment

The project is configured for Cloudflare Workers using the OpenNext Cloudflare adapter.

Live deployment:

- `https://v-market.vmarket-vietdung2005.workers.dev`
- Worker name: `v-market`
- Latest verified Worker Version ID: `d15a14fb-70af-4861-8231-3cdc0b72a927`

Official Cloudflare guidance for full-stack Next.js apps is Workers + OpenNext. The repository follows that shape:

- `@opennextjs/cloudflare`
- `wrangler`
- `open-next.config.ts`
- `wrangler.jsonc`
- `public/_headers`
- R2 bucket binding `V_MARKET_ORDERS` for checkout order JSON.

## Required Local Environment

The deploy helper reads `D:\Project_Frontend2\.env` and maps:

- `ACCOUNT_ID` -> `CLOUDFLARE_ACCOUNT_ID`
- `API_TOKEN` -> `CLOUDFLARE_API_TOKEN`

The helper does not copy secrets into the repository.

The current `.env` is sufficient for Wrangler authentication and deployment. During setup:

- The Workers account subdomain `vmarket-vietdung2005` was registered because the account did not have a `workers.dev` subdomain yet.
- R2 bucket `v-market-orders` was created and bound to the Worker.

## Commands

Local verification:

```bash
npm run test
npm run lint
npm run build
```

Dependency audit:

```bash
npm audit --audit-level=high
```

Cloudflare production-like preview:

```bash
npm run preview
```

Deploy with the provided parent `.env`:

```powershell
powershell -ExecutionPolicy Bypass -File tools\deploy-cloudflare.ps1
```

## Cloudflare Account Requirements

The API token must be allowed to deploy Workers for the account.

Because the app uses Next image optimization on Cloudflare Workers, the account also needs Cloudflare Images support for the `IMAGES` binding in `wrangler.jsonc`.

This account accepted the `IMAGES` binding during deploy.

Production order persistence was verified by posting to `/api/orders` and reading the resulting `orders/<id>.json` object from R2.

Most recent verification:

- Browser QA passed against `https://v-market.vmarket-vietdung2005.workers.dev`.
- Invalid live order with unknown product returned HTTP 400.
- Valid live order `VM-MS17Z0PH` returned `storage: "r2"` and was read back from `v-market-orders/orders/VM-MS17Z0PH.json`.
