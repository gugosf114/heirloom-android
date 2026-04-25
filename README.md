# Heirloom

Photo restoration for old, damaged family photos.

**For your family. Not your followers.**

## What it is

A single-screen Android app that restores old, scratched, faded, or low-quality
family photos. The pipeline is honest restoration — not selfie enhancement —
and explicitly refuses to silently return a result when face identity drifts
too far from the input.

## What it isn't

- A face filter
- A subscription trap (one-time $4.99 unlock or $9.99/year, no auto-renew tricks)
- A watermark vendor (no watermarks, no ads, ever)
- A surveillance product (photos are processed and discarded; nothing stored)

## Architecture

```
Android client (Kotlin, Compose)
        │
        │ multipart upload
        ▼
Cloudflare Worker proxy
        │
        │ orchestrates pipeline
        ▼
Replicate
  1. Bringing-Old-Photos-Back-to-Life  (scratch/tear repair)
  2. CodeFormer fidelity 0.7           (face restoration)
  3. Real-ESRGAN                       (upscale + denoise)
  4. AdaFace cosine sim                (identity gate, threshold 0.6)
  5. DDColor                           (colorize, B&W input only)
```

If AdaFace cosine similarity drops below the threshold, the worker returns the
restored image **with a warning** flag — it does not silently pass.

## Free in Armenia

Detected via Play Store account country + device locale. AM = no paywall, ever.

## Repo layout

```
app/                    Android source (Kotlin + Compose)
worker/                 Cloudflare Worker proxy
cog-adaface/            Self-hosted AdaFace identity gate (Replicate Cog)
gradle/                 Wrapper
```

The AdaFace gate is self-hosted because no active dedicated face-embedding
model exists on Replicate as of 2026-04-25 (`apna-mart/face-match` is
offline; no `insightface`/`arcface` alternatives are alive). See
[`cog-adaface/README.md`](./cog-adaface/README.md) for the model choice
(IR-101 / WebFace4M from the AdaFace paper authors) and
[`cog-adaface/deploy.md`](./cog-adaface/deploy.md) for build + push steps.

## Build

```
gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Worker deploy

```
cd worker
npm install
npx wrangler deploy
```

Set the Replicate token as a secret:

```
npx wrangler secret put REPLICATE_API_TOKEN
```
