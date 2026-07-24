# Heirloom edge gateway

The Cloudflare Worker is the authenticated public edge for Heirloom:

```text
Android app
  -> Google Play Integrity + Billing
  -> Cloudflare Worker (server credit ledger, reports, streaming proxy)
  -> Google Cloud Run (all restoration models, scale-to-zero L4)
```

No restoration photo is sent to Replicate. The Worker streams request and
response bodies without storing them.

## Routes

- `GET /health` — checks the Cloud Run service.
- `POST /billing/sync` — verifies the Play-installed app and purchase receipts,
  then returns a short-lived restoration session.
- `POST /restore` — credit-gated buffered restoration proxy.
- `POST /restore-stream` — credit-gated NDJSON restoration proxy.
- `POST /report` — session-authenticated result report without the photo.

## Configuration

`wrangler.toml` contains the non-secret Cloud Run URL and the `REPORTS` KV
binding. Set these secrets separately for production and development:

```bash
npx wrangler secret put APP_SHARED_SECRET
npx wrangler secret put PIPELINE_SHARED_SECRET
npx wrangler secret put APP_SHARED_SECRET --env dev
npx wrangler secret put PIPELINE_SHARED_SECRET --env dev
```

`APP_SHARED_SECRET` must match `HEIRLOOM_APP_KEY` for development builds.
Production uses Play Integrity and server sessions instead of treating an
APK-embedded key as proof of purchase. `PIPELINE_SHARED_SECRET` is a separate
server-to-server secret shared only with Cloud Run.

Production also requires:

```bash
npx wrangler secret put GOOGLE_SERVICE_ACCOUNT_JSON
npx wrangler d1 migrations apply heirloom-billing --remote
```

The Google service account must have Play Console access to verify,
acknowledge, and consume Heirloom's one-time products. The `BILLING` D1
database owns the three-credit trial, paid balances, idempotent reservations,
and refunds for failed restorations.

Reports contain a reason, optional text, restoration metadata, and app version.
They never contain the selected or restored photo and expire after 90 days.

## Verify and deploy

```bash
npm ci
npm test
npx tsc --noEmit
npx wrangler whoami
npx wrangler deploy
npx wrangler deploy --env dev
```

## End-to-end smoke test

```bash
SMOKE_TEST_SECRET=... npm run smoke:remote
```

The smoke test uses the public-domain *Migrant Mother* image and requires all
five Cloud Run stages to reach a final inline JPEG with a real identity score.
