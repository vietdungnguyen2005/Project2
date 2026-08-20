# Cloudflare frontend deployment

The OpenNext worker hosts the Next.js UI and BFF. Set these encrypted Worker secrets:

- `BACKEND_ORIGIN`: HTTPS origin of the Java backend.
- `BFF_SHARED_SECRET`: same strong value configured on Spring Boot.
- `VMARKET_OPS_SECRET`: separate strong value for the operations console.

GitHub Actions deployment additionally needs `CLOUDFLARE_ACCOUNT_ID` and a narrowly scoped `CLOUDFLARE_API_TOKEN`. The current workflow verifies builds but does not deploy automatically; this avoids publishing an unreviewed revision.

Run `npm run preview` for an OpenNext preview and `npm run deploy` only when the backend origin and secrets are ready. The frontend returns an explicit `503 BACKEND_UNAVAILABLE` instead of fabricating catalog or order data when no backend is connected.
