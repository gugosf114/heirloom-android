# Heirloom Worker

Cloudflare Worker that orchestrates the photo restoration pipeline on Replicate.

## Setup

```bash
npm install
npx wrangler login
npx wrangler secret put REPLICATE_API_TOKEN
```

## Pin model versions

Replicate models are versioned by SHA. The defaults in `wrangler.toml` are
placeholders. Get the live version SHAs from each model's Replicate page,
then either edit `wrangler.toml` directly or override per environment:

```bash
npx wrangler secret put BOPB_VERSION
npx wrangler secret put CODEFORMER_VERSION
npx wrangler secret put ESRGAN_VERSION
npx wrangler secret put ADAFACE_VERSION
npx wrangler secret put DDCOLOR_VERSION
```

Models referenced (community-hosted on Replicate):

- Bringing-Old-Photos-Back-to-Life — Microsoft Research port
- `sczhou/codeformer` — face restoration
- `nightmareai/real-esrgan` — upscale + denoise
- AdaFace — face identity (cosine similarity)
- `piddnad/ddcolor` — colorization

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

`POST /restore` — multipart form, `image` field with JPEG bytes.

```json
{
  "restored_url": "https://replicate.delivery/.../output.jpg",
  "cosine_similarity": 0.84,
  "identity_warning": false,
  "was_colorized": true
}
```

When `identity_warning` is true, the client surfaces a warning banner. The
restored URL is still returned — we don't gatekeep the result, we tell the
truth about it.
