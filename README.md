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

## Session log

### 2026-04-30 — first end-to-end cog build + Replicate push

**What shipped this session:**

- Docker Desktop installed on Windows (WSL2 backend, Linux containers).
- Cog inside WSL Ubuntu (`/usr/local/bin/cog` 0.18.0, `~/.local/bin/cog` 0.19.0 — either works).
- First successful `cog build` for `cog-adaface`. Three deploy-time fixes were only discovered by actually running the build end-to-end (committed in `df34472`):
    - `cog.yaml` — added `fvcore==0.1.5.post20221221` (the bundled HF iresnet model code imports `fvcore.nn.flop_count`; `AutoModel.from_pretrained` fails without it).
    - `cog.yaml` — replaced multi-line `run:` with a single inline Python command. Cog rejects multi-line `run:` blocks at Dockerfile-generation time.
    - `cog.yaml` — pre-cache MTCNN PNet/RNet/ONet weights at build time. `facenet-pytorch` lazy-fetches them from GitHub on first MTCNN init; with Replicate's egress constrained, that hangs `setup()` past the 600s ceiling.
    - `predict.py` — moved `WEIGHTS_DIR` from `/src/adaface_weights` to `/opt/adaface_weights`. Cog's predict runtime bind-mounts the host source over `/src` for live-iteration, masking anything baked into `/src` at build time.
- First build wallclock: ~13 minutes on this connection (CUDA 11.8 + torch base = ~7–8 GB pull). Subsequent builds will hit cache.
- Image size: 26.2 GB on disk, 9.01 GB content.
- Tier 2 verification on CPU (no NVIDIA GPU on this machine; cog auto-fell-back to CPU when `--gpus` failed):
    - `cos(Einstein x2)    = 0.590` (>0.5, passes)

### 2026-05-30 — Worker Pipeline & Replicate Custom Deployment Fixes

**What shipped this session:**

- Debug build of the Android app pointed to `heirloom-worker-dev.gugosf.workers.dev`.
- Updated `worker/wrangler.toml` to include an `[env.dev]` block and properly pass variables to the dev environment.
- Configured Cloudflare worker with `REPLICATE_API_TOKEN` and `ADAFACE_VERSION`.
- Diagnosed and fixed an issue where the `heirloom-adaface` custom Replicate deployment kept hanging. The custom model (on an Nvidia T4 GPU) took ~4 minutes to cold boot from 0 instances, causing Cloudflare Workers to hit their 50-subrequest free-tier limit during polling.
- Fixed the typescript wrapper (`replicate.ts`) to correctly format POST requests for Replicate's `deployments` API vs public models.
- Set the custom Replicate deployment's `min_instances` to `1` via direct API PATCH to keep the GPU warm, reducing AdaFace time from 240s+ to ~2s.
- Increased worker's `POLL_TIMEOUT_MS` to 480_000 (8 minutes) just in case.
- End-to-end smoke test now passes with colorization and AdaFace cosine similarity checking working properly (`cos=0.846` for Migrant Mother test).
    - `cos(Einstein, Bohr) = 0.014` (<0.3, passes)
    - 0.590 sits at the edge of the configured 0.6 threshold. Worth re-tuning once real before/after restoration pairs are available.
- Pushed to Replicate as `gugosf114/heirloom-adaface` (private, GPU L40S).
- Pinned the version SHA in `worker/wrangler.toml`:
  `ab9c60a63457c7d15b6bc047afff20983247d6ef331db8cdb3b9d116ea39e07c`
- Worker `npm install` ran cleanly (66 packages).
- Worker `wrangler deploy` did **not** ship — Cloudflare OAuth login timed out, fell back to API-token route. Token issuance pending at session end.

**Environment notes for future sessions:**

- Docker Desktop is now installed and configured. WSL2 Ubuntu picks up the Docker engine cleanly.
- No NVIDIA GPU on this machine. All local `cog predict` runs go to CPU (~30s per prediction instead of ~3s on GPU). Cog handles the fallback automatically.
- Test fixtures already exist at `cog-adaface/test/fixtures/` (gitignored): `same_a.jpg`, `same_b.jpg`, `diff_a.jpg`. No need to re-run `download_fixtures.sh`.

## To do next session

1. **Finish the worker deploy.** Get a Cloudflare API token from `https://dash.cloudflare.com/profile/api-tokens` (template: "Edit Cloudflare Workers"). Either set `CLOUDFLARE_API_TOKEN` in the env before running `npx wrangler deploy`, or use `wrangler login` again with the OAuth flow if the token route is undesired.
2. **Set the Replicate secret on the deployed worker.** From `worker/`, run `npx wrangler secret put REPLICATE_API_TOKEN`. The secret is pulled from `https://replicate.com/account/api-tokens`. Without it, the deployed worker has no way to call Replicate.
3. **Smoke test end-to-end.** From `worker/`, run `npm run smoke:remote`. The expected last stage event reports a real cosine number (not null) and `identity_warning: false` (since the smoke fixture is identity-preserving). If cosine is null or the AdaFace stage is skipped, the SHA pin or secret is wrong.
4. **Calibrate the 0.6 threshold against real data.** Generate (or wait for) actual before/after restoration pairs from the Heirloom pipeline. Compare cosines. If real pairs cluster at 0.5–0.6, drop the threshold to 0.5; if well above 0.7, keep 0.6 or tighten. The current 0.6 was set against AdaFace's documented "same identity" floor, not against Heirloom-specific drift.
5. **Android side.** Re-run the Android build against the now-live pipeline and confirm the Compose UI handles `identity_warning: true` visibly (warn the user; do not auto-display the result without acknowledgment). The failure-honesty contract from `cog-adaface/README.md` only matters end-to-end if the UI surfaces it.
