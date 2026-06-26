# Heirloom pipeline — Cloud Run (L4, scale-to-zero), fully local

All 5 restoration models run **in this one container** on an L4 that **scales
to zero** when idle. No Replicate, no API token, no per-call dependency on
anyone — the compute is yours. Idle cost: **$0**; you pay cents per restore.

Why this exists: the old design self-hosted the AdaFace identity gate on a
Replicate deploy pinned to `min_instances=1` — a GPU billing 24/7. This kills
that, and (per the "off Replicate entirely" call) brings the other four models
in-house too.

## Contract (matches the old Worker)

```
GET  /health           -> {"ok": true}
POST /restore          multipart {image} -> JSON PipelineResult
POST /restore-stream   multipart {image} -> NDJSON stage events
```

Stage events (`auto_level`, `bopb`, `codeformer`, `esrgan`, `adaface`,
`colorize_check`, `ddcolor`, `grain_synthesis`, `final`) and the
`PipelineResult` fields match `worker/src/index.ts` — **except** `restored_url`
is now a `data:image/jpeg;base64,...` URL (the finished image streamed back, not
a stored remote file). The stream feeds the "live steps + Restoration Report"
wait UI.

## The 5 stages

| stage | model | how it runs |
|-------|-------|-------------|
| bopb | Bringing-Old-Photos-Back-to-Life (scratch repair) | vendored repo, own venv (old torch + dlib) |
| codeformer | CodeFormer 0.7 (face) | vendored repo, own venv |
| esrgan | Real-ESRGAN x2 (upscale) | in-process (realesrgan pkg) |
| adaface | AdaFace IR-101 (identity gate) | in-process (ports cog-adaface) |
| ddcolor | DDColor large (colorize, B&W only) | vendored repo, own venv |

Each old/conflicting model gets its **own venv** inside the image and is invoked
as a subprocess, so their pinned deps never collide. AdaFace + ESRGAN are clean
enough to run in the main process.

## Deploy

```
cd pipeline
./deploy.sh          # gcloud run deploy with L4 / scale-to-zero flags
```

No secrets to set — there's no external API.

## Smoke

```
URL=$(gcloud run services describe heirloom-pipeline --region us-central1 --format='value(status.url)')
curl -N -F image=@some_old_photo.jpg "$URL/restore-stream"
```

Expected: stage events end with a `final` carrying a real `cosine_similarity`
and a `restored_url` data URL.

## Status

**All 5 stages ported; orchestrator is fully local (no Replicate). NOT yet
built.** The next milestone is the first Cloud Build — a large image (CUDA +
torch + 3 isolated model venvs + several GB of weights). Expect a few
iterations: the most likely first failures are the BOPB torch pin, the vendored
checkpoint/weight URLs (CodeFormer / DDColor / BOPB), and the basicsr fork
inside CodeFormer's venv. GPU access on `bakers-agent` is already confirmed (an
L4 test deploy succeeded 2026-06-26).
