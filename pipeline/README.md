# Heirloom pipeline — Cloud Run (L4, scale-to-zero), fully local

All 5 restoration models run **in this one container** on an L4 that **scales
to zero** when idle. No Replicate, no API token, no per-call dependency on
anyone — the compute is yours. Active processing costs cents per restore.
Cloud Run may keep a GPU instance warm briefly after an isolated request, so
the billed cost of a lone restoration can be higher than its processing time.

This replaces the previous third-party inference path and keeps the entire
restoration pipeline in the project's Google Cloud service.

## Contract (matches the old Worker)

```
GET  /health           -> {"ok": true}
POST /restore          multipart {image} -> JSON PipelineResult
POST /restore-stream   multipart {image} -> NDJSON stage events
```

Stage events (`bopb`, `codeformer`, `esrgan`, `adaface`, `colorize_check`,
`ddcolor`, `final`) describe only work the server actually performs.
`restored_url` is a `data:image/jpeg;base64,...` URL: the finished image is
streamed back instead of stored remotely.

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

The deploy expects a Secret Manager secret named
`heirloom-pipeline-shared-secret`. Cloudflare sends this value in the
`X-Pipeline-Key` header. The health endpoint remains public.

## Smoke

```
URL=$(gcloud run services describe heirloom-pipeline --region us-central1 --format='value(status.url)')
curl -N -H "X-Pipeline-Key: <secret>" \
  -F image=@some_old_photo.jpg "$URL/restore-stream"
```

Expected: stage events end with a `final` carrying a real `cosine_similarity`
and a `restored_url` data URL.

## Production behavior

The service uses one request per GPU instance, scales to zero when idle, and
allows at most one instance. Each request uses a temporary directory that is
removed after the response completes.
