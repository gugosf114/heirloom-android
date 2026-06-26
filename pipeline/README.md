# Heirloom pipeline — Cloud Run (L4, scale-to-zero)

This replaces the cost problem in the original architecture: the AdaFace
identity gate was a **custom Replicate deploy pinned to `min_instances=1`** —
a GPU billing 24/7 whether anyone used the app or not. The 4 other models
(BOPB / CodeFormer / Real-ESRGAN / DDColor) are **public** Replicate models
that already bill per-use with no idle cost.

So this service:

- Runs the **AdaFace gate locally** on a Cloud Run L4 that **scales to zero**
  when idle → the 24/7 GPU bill goes to **$0**; you pay cents per restore.
- Keeps the orchestration + Replicate polling **here** (not in a Cloudflare
  Worker), so the Worker's 50-subrequest cap no longer applies.
- Still calls the 4 public Replicate models per-use. A later pass can fold
  them in-container too for a fully Replicate-free pipeline (BOPB is the
  hardest to package — Microsoft's repo, old deps).

## Contract (identical to the old Worker)

```
GET  /health           -> {"ok": true}
POST /restore          multipart {image} -> JSON PipelineResult
POST /restore-stream   multipart {image} -> NDJSON stage events
```

Stage events (`auto_level`, `bopb`, `codeformer`, `esrgan`, `adaface`,
`colorize_check`, `ddcolor`, `grain_synthesis`, `final`) and the
`PipelineResult` fields (`restored_url`, `cosine_similarity`,
`identity_warning`, `was_colorized`, `adaface_skipped`) match
`worker/src/index.ts` byte-for-byte — the Android app needs no contract change.
The streaming endpoint is what feeds the new "live steps + Restoration Report"
wait UI.

## Layout

```
app/server.py           FastAPI app + pipeline orchestration (ports index.ts)
app/adaface.py          local AdaFace gate (ports cog-adaface/predict.py)
app/face_align.py       ArcFace 5-pt alignment (verbatim from cog-adaface)
app/replicate_client.py async Replicate client (ports replicate.ts; 2s poll, no subrequest cap)
app/grayscale.py        B&W detector (ports saturation.ts)
Dockerfile              CUDA 11.8 / py3.10; bakes AdaFace + MTCNN weights
deploy.sh               gcloud run deploy with the proven L4 / scale-to-zero flags
```

## Deploy

```
cd pipeline
# one-time: store the Replicate token (see deploy.sh header)
./deploy.sh
```

## Smoke

```
URL=$(gcloud run services describe heirloom-pipeline --region us-central1 --format='value(status.url)')
curl -N -F image=@some_old_photo.jpg "$URL/restore-stream"     # watch the NDJSON stages
```

Expected: a `final` event with a real `cosine_similarity` (not null) and, for
an identity-preserving photo, `identity_warning: false`.

## Status

**Scaffold — not yet built/deployed.** Code is ported from the proven Worker +
cog and config-matched to wrangler.toml. Next milestone is the first Cloud
Build (CUDA + torch + weights → a multi-GB image, several minutes) and the
smoke test above; expect a couple of dependency iterations on the first build.
GPU access on `bakers-agent` is already confirmed (an L4 test deploy succeeded
2026-06-26).
