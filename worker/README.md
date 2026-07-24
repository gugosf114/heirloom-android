# Heirloom edge gateway

The Cloudflare Worker is the authenticated public edge for Heirloom:

```text
Android app
  -> Cloudflare Worker (app key, reports, streaming proxy)
  -> Google Cloud Run (all restoration models, scale-to-zero L4)
```

No restoration photo is sent to Replicate. The Worker streams request and
response bodies without storing them.

## Routes

- `GET /health` — checks the Cloud Run service.
- `POST /restore` — authenticated buffered restoration proxy.
- `POST /restore-stream` — authenticated NDJSON restoration proxy.
- `POST /report` — stores a user-submitted result report without the photo.

## Configuration

`wrangler.toml` contains the non-secret Cloud Run URL and the `REPORTS` KV
binding. Set these secrets separately for production and development:

```bash
npx wrangler secret put APP_SHARED_SECRET
npx wrangler secret put PIPELINE_SHARED_SECRET
npx wrangler secret put APP_SHARED_SECRET --env dev
npx wrangler secret put PIPELINE_SHARED_SECRET --env dev
```

`APP_SHARED_SECRET` must match the Android release build's
`HEIRLOOM_APP_KEY`. `PIPELINE_SHARED_SECRET` is a separate server-to-server
secret shared only with Cloud Run.

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
APP_SHARED_SECRET=... npm run smoke:remote
```

The smoke test uses the public-domain *Migrant Mother* image and requires all
five Cloud Run stages to reach a final inline JPEG with a real identity score.
