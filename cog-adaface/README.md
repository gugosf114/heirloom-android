# cog-adaface

Self-hosted Cog container that wraps [AdaFace](https://arxiv.org/abs/2204.00964)
as the identity-verification gate for the Heirloom restoration pipeline.

Deployed as a private model on Replicate; called by the Heirloom Worker
between Real-ESRGAN (output) and DDColor (potentially), comparing the
original input against the restored output. Cosine similarity below the
configured threshold (default 0.6) surfaces an `identity_warning: true`
to the Android client.

## Why we self-host

No active dedicated face-embedding model exists on Replicate as of
2026-04-25. `apna-mart/face-match` is offline; no `insightface`,
`arcface`, or `facenet` model is alive on the platform. Self-hosting
AdaFace via Cog is the cleanest way to land an honest identity gate.

## Model

`minchul/cvlface_adaface_ir101_webface4m` from the AdaFace paper authors:

- **Architecture:** IR-101 (Improved ResNet-101 backbone)
- **Training set:** WebFace4M (largest publicly available face dataset)
- **Output:** 512-dim feature embedding
- **Format:** safetensors, loaded via `transformers.AutoModel + trust_remote_code=True`
- **Why this variant:** 1398 HF downloads, the AdaFace paper's strongest
  configuration. ~250 MB weights, ~1.5x slower than IR-50 — accuracy >>
  latency for an identity-verification use case where false acceptance
  would silently pass a bad restoration.

The HF repo bundles the model architecture (`models/iresnet/model.py`),
the inference wrapper (`wrapper.py`), and the safetensors weights in one
self-contained package. We deliberately load via the author's own
`transformers`-compatible API instead of hand-rewriting the iresnet
architecture — eliminates architecture-mismatch risk that plagues
hand-ported model code.

## Layout

```
cog-adaface/
├── cog.yaml              build config (CUDA 11.8, torch 2.0.1, deps)
├── download_weights.py   build-time HF snapshot to /src/adaface_weights/
├── predict.py            Predictor class: setup + predict
├── face_align.py         5-point ArcFace alignment template
├── README.md             this file
├── deploy.md             step-by-step build + push to Replicate
├── .gitignore            weights, fixtures, .cog/, __pycache__
└── test/
    ├── local_test.py             two-tier: structural + cosine
    ├── download_fixtures.sh      pull Wikimedia PD face fixtures
    └── fixtures/.gitkeep         (real fixtures gitignored)
```

## Pipeline (per prediction)

```
image1 ─┐
        ├─ MTCNN detect (5-point landmarks, top-1 face)
        │
        ├─ ArcFace 5-pt similarity transform → 112×112 RGB
        │
        ├─ Normalize to [-1, 1]   (AdaFace input convention)
        │
        ├─ AdaFace IR-101 forward → 512-d feature
        │
image2 ─┘                              │
                                        ▼
                       cos(L2(emb1), L2(emb2)) ∈ [-1, 1]
```

If MTCNN finds no face in either image, the predictor returns
`cosine_similarity: null` plus per-image detection flags. The Heirloom
Worker treats `null` as identity-warning territory — silent passes are
the worst outcome, so absent verification surfaces the warning.

## Output schema

```json
{
  "cosine_similarity": 0.84,
  "image1_face_detected": true,
  "image2_face_detected": true,
  "image1_confidence": 0.999,
  "image2_confidence": 0.998
}
```

When face detection fails:

```json
{
  "cosine_similarity": null,
  "image1_face_detected": false,
  "image2_face_detected": true,
  "image1_confidence": 0.0,
  "image2_confidence": 0.998
}
```

## Local test

Two tiers. Tier 1 is the always-on structural check; Tier 2 is the
cosine-correctness check that needs real weights.

### Tier 1 — structural (no weights, no GPU)

```bash
cd cog-adaface
pip install numpy pillow opencv-python-headless torch
python test/local_test.py
```

Expected:

```
Ran 5 tests in 0.3s
OK
```

This stubs `cog`, mocks MTCNN and AutoModel, and verifies the predictor
shape, schema, and graceful null-cosine behavior on no-face input.

### Tier 2 — cosine correctness (real weights, real fixtures)

```bash
cd cog-adaface

# Pull weights into ./adaface_weights/
TARGET_DIR=$(pwd)/adaface_weights python -c \
  "import os, download_weights as dw; dw.TARGET_DIR=os.environ['TARGET_DIR']; dw.main()"

# Pull real face fixtures
bash test/download_fixtures.sh

# Install runtime deps
pip install transformers facenet-pytorch huggingface_hub safetensors omegaconf pyyaml

# Run, forcing CPU
USE_CPU=1 python test/local_test.py --full
```

Tier 2 asserts:

- `cos(same_a, same_b) > 0.5` (two distinct Einstein photos)
- `cos(same_a, diff_a) < 0.3` (Einstein vs Niels Bohr)

## Build and deploy

See [deploy.md](./deploy.md). Summary:

```bash
cd cog-adaface
cog build -t heirloom-adaface           # ~10-15 min, downloads weights
cog predict -i image1=@test/fixtures/same_a.jpg -i image2=@test/fixtures/same_b.jpg
cog login                               # one-time
cog push r8.im/gugosf114/heirloom-adaface
```

The push outputs the new version SHA. Replace `ADAFACE_VERSION` in
`worker/wrangler.toml` with that SHA.

## Known risks

- **HF wrapper relative paths.** The bundled `wrapper.py` uses
  `open('pretrained_model/model.yaml')` — `predict.py:setup()` chdirs
  into the weights dir before calling `AutoModel.from_pretrained`.
  Skipping the chdir produces a silent FileNotFoundError at first
  inference (caught + reported as detection failure, but masks a real
  config bug).
- **MTCNN `select_largest=True`.** We pick the top face by area, not by
  centrality or by detection confidence. For Heirloom, the input image
  almost always centers on one subject — area-largest is the right
  heuristic. For multi-subject group photos, behavior is intentional:
  we verify the dominant face, not all of them.
- **Image normalization.** `(x/255 - 0.5) / 0.5 → [-1, 1]` is the
  AdaFace convention. Mismatch (e.g., ImageNet mean/std) silently
  produces valid-looking but garbage embeddings. Documented to prevent
  drift.
- **CPU fallback (`USE_CPU=1`).** ~30x slower than GPU; debug only.
  Production runs expect Replicate's GPU.
- **Embedding norm collapse.** Returns `cosine_similarity: null` with
  `error: "embedding norm collapsed to zero"` if either embedding is
  effectively zero. Vanishingly rare on real face crops, but worth
  surfacing rather than dividing by zero.
