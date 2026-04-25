# Heirloom Worker

Cloudflare Worker that orchestrates the photo restoration pipeline on Replicate.

## Setup

```bash
npm install
npx wrangler login
npx wrangler secret put REPLICATE_API_TOKEN
```

## Pinned model versions

Verified active on Replicate as of 2026-04-25 (`modelStatus: online`):

| Model | Slug | Last published |
|---|---|---|
| BOPB | `microsoft/bringing-old-photos-back-to-life` | 2022-09-28 (stale, monitor) |
| CodeFormer | `sczhou/codeformer` | 2025-01-20 |
| Real-ESRGAN | `nightmareai/real-esrgan` | latest_version pin |
| DDColor | `piddnad/ddcolor` | 2024-01-12 |
| AdaFace | **none active on Replicate** — placeholder pin disables the gate |

The AdaFace gate is graceful-skip: if `ADAFACE_VERSION` doesn't match
`/^[a-f0-9]{64}$/`, the pipeline returns `cosine_similarity: null,
identity_warning: false, adaface_skipped: true`. Decide a substitute
(self-hosted Cog, CLIP image-similarity, ImageBind, etc.) before
launching to users who deserve the identity check.

To override versions per environment:

```bash
npx wrangler secret put BOPB_VERSION
npx wrangler secret put CODEFORMER_VERSION
npx wrangler secret put ESRGAN_VERSION
npx wrangler secret put ADAFACE_VERSION
npx wrangler secret put DDCOLOR_VERSION
```

## Run locally

```bash
npm run dev
```

Then the Android client points at `http://localhost:8787/restore` (set
`WORKER_BASE_URL` in `app/build.gradle.kts`).

## Deploy

```bash
npm run deploy           # production: heirloom-worker
npm run deploy:dev       # dev: heirloom-worker-dev
```

## API

### `POST /restore` — buffered single-response

Multipart form with `image` field (JPEG, ≤5 MB).

```json
{
  "restored_url": "https://replicate.delivery/.../output.jpg",
  "cosine_similarity": 0.84,
  "identity_warning": false,
  "was_colorized": true,
  "adaface_skipped": false
}
```

When `identity_warning` is true, the client surfaces a warning banner. The
restored URL is still returned — we don't gatekeep the result, we tell the
truth about it.

When `adaface_skipped` is true, `cosine_similarity` is `null` and the
identity check did not run.

### `POST /restore-stream` — NDJSON stage stream

Same multipart input. Response is `application/x-ndjson` where each line
is a `StageEvent`:

```json
{"kind":"stage_start","stage":"bopb","t_ms":12}
{"kind":"stage_done","stage":"bopb","t_ms":15834,"output_url":"https://...","extra":{"duration_ms":15822}}
{"kind":"stage_start","stage":"codeformer","t_ms":15835}
{"kind":"stage_done","stage":"codeformer","t_ms":31402,"output_url":"https://...","extra":{"duration_ms":15567,"fidelity":0.7}}
...
{"kind":"final","t_ms":78211,"result":{"restored_url":"...","cosine_similarity":0.84,...}}
```

Used by `npm run smoke` for per-stage timing observability.

## Smoke test

```bash
npm install                           # one time
npx wrangler dev                      # in another terminal
npx wrangler secret put REPLICATE_API_TOKEN     # local: writes to .dev.vars
npm run smoke                         # downloads Migrant Mother, runs pipeline
```

Or against a deployed Worker:

```bash
npm run smoke:remote
```

The smoke test asserts the AdaFace gate fires correctly (either with a
real cosine value, or `adaface_skipped: true`) and that B&W input gets
colorized.
