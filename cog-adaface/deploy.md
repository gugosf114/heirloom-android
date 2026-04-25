# Deploying cog-adaface to Replicate

Step-by-step. Run from `cog-adaface/`.

## Prerequisites

- Docker Desktop running
- [Cog](https://github.com/replicate/cog) installed
  ```bash
  curl -o /usr/local/bin/cog -L "https://github.com/replicate/cog/releases/latest/download/cog_$(uname -s)_$(uname -m)"
  chmod +x /usr/local/bin/cog
  cog --version
  ```
- A Replicate account at https://replicate.com (`gugosf114`)
- A Replicate API token: https://replicate.com/account/api-tokens

## 1. Create the model on Replicate (one-time)

In the Replicate dashboard:

1. Click **Create a model**
2. Name: `heirloom-adaface`
3. Owner: `gugosf114`
4. Visibility: **Private** (you'll never want this public — it's our
   identity gate, only the Heirloom Worker should call it)
5. Hardware: **GPU L40S** or **A100** (T4 also works; ~3x slower)
6. License: leave blank (private), or "Other" with note "Internal use only"

Copy the slug `gugosf114/heirloom-adaface`.

## 2. Authenticate Cog

```bash
cog login
# paste your Replicate API token when prompted
```

## 3. Build locally

```bash
cd cog-adaface
cog build -t heirloom-adaface
```

What this does:

1. Downloads the CUDA 11.8 base image (~2 GB, cached after first run)
2. Installs Python deps (~1 GB)
3. Runs `python /src/download_weights.py` — pulls AdaFace from HF
   into `/src/adaface_weights/` (~250 MB)
4. Bakes everything into a Docker image tagged `heirloom-adaface`

Expected: ~10-15 min on first build, ~1-2 min on rebuild after a
predict.py edit (Docker layer cache).

If the HF download step 404s, the most likely cause is the `REPO_ID` in
`download_weights.py` — at the time of writing,
`minchul/cvlface_adaface_ir101_webface4m` was live with 1398 downloads.
Verify at https://huggingface.co/minchul/cvlface_adaface_ir101_webface4m
and update if the path has changed.

## 4. Verify locally before pushing

```bash
# Pull real face fixtures
bash test/download_fixtures.sh

# Run a real prediction inside the cog container
cog predict \
  -i image1=@test/fixtures/same_a.jpg \
  -i image2=@test/fixtures/same_b.jpg
```

Expected output (cosine should be > 0.5 for two Einstein photos):

```json
{
  "cosine_similarity": 0.78,
  "image1_face_detected": true,
  "image2_face_detected": true,
  "image1_confidence": 0.999,
  "image2_confidence": 0.997
}
```

Then verify negative case:

```bash
cog predict \
  -i image1=@test/fixtures/same_a.jpg \
  -i image2=@test/fixtures/diff_a.jpg
```

Cosine should be < 0.3 (Einstein vs Bohr).

If the cosines come out wrong, do **not** push — investigate. Most
likely culprits: input normalization mismatch (AdaFace expects [-1, 1],
not ImageNet stats), wrong alignment template, or the wrapper.py chdir
failing silently.

## 5. Push to Replicate

```bash
cog push r8.im/gugosf114/heirloom-adaface
```

Cog will:

1. Push image layers to Replicate's registry (~3-5 min depending on uplink)
2. Spin up a fresh container on Replicate hardware to verify it boots
3. Print the new version SHA — looks like `abc123...` (64 hex chars)

## 6. Wire the Worker

Update `worker/wrangler.toml`:

```toml
ADAFACE_VERSION = "<new SHA from cog push>"
```

Then redeploy the Worker:

```bash
cd worker
npx wrangler deploy
```

## 7. Smoke-test end-to-end

```bash
cd worker
npm run smoke           # local
# or
npm run smoke:remote    # production
```

The smoke test asserts AdaFace either reports a real cosine OR is
explicitly skipped. With `ADAFACE_VERSION` now pinned, expect a real
cosine number in the final stage event.

## Cost estimate

Replicate prices per-second on the chosen hardware. With ~3-4s wallclock
per AdaFace prediction (GPU warm) on **L40S** at ~$0.000965/s:

- ~$0.003 per prediction (rounding up)
- 1000 restorations / month = ~$3
- 10000 restorations / month = ~$30

Comparable to a CodeFormer call on similar hardware. The identity gate
isn't the cost driver in the pipeline — Real-ESRGAN and CodeFormer are.

For private models, Replicate also charges a per-deployment fee for the
underlying GPU instance (currently zero idle cost — pay-per-prediction
only). Verify on https://replicate.com/pricing before launching.

## Update path (re-deploys)

When AdaFace upstream releases improved weights, or you tune predict.py:

```bash
cog build
cog push r8.im/gugosf114/heirloom-adaface
# → new SHA, drop into wrangler.toml, redeploy worker
```

Replicate keeps old versions available indefinitely, so rollback is
cheap: just point `ADAFACE_VERSION` at the previous SHA and redeploy
the worker.
