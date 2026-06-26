"""
Heirloom restoration pipeline — Cloud Run (L4, scale-to-zero) edition.

Same contract as the original Cloudflare Worker (worker/src/index.ts):
  GET  /health
  POST /restore         multipart { image } -> single JSON PipelineResult
  POST /restore-stream  multipart { image } -> NDJSON stream of stage events

What changed vs the Worker:
  - The AdaFace identity gate runs LOCALLY on this container's GPU instead of
    the always-warm Replicate custom deploy. That removes the 24/7 GPU bill;
    this instance scales to zero when idle.
  - Orchestration + Replicate polling happen here, not in a Worker, so the
    Cloudflare 50-subrequest cap no longer applies.
  - The 4 standard models (BOPB / CodeFormer / Real-ESRGAN / DDColor) are still
    public Replicate models — they bill per-use, $0 idle. (A later pass can
    fold them in-container too, for a fully Replicate-free pipeline.)

Stage events and PipelineResult fields are byte-for-byte the shapes the
Android app already understands.
"""

import base64
import io
import os
import tempfile
import time
from typing import Any, AsyncGenerator, Dict, Optional

import httpx
from fastapi import FastAPI, UploadFile, File
from fastapi.responses import JSONResponse, StreamingResponse

from .adaface import GATE
from .grayscale import is_grayscale
from .replicate_client import run_replicate, as_url

# --- pinned config (overridable via env; defaults match worker/wrangler.toml) ---
def cfg(name: str, default: str) -> str:
    return os.getenv(name, default)

BOPB_VERSION = cfg("BOPB_VERSION", "c75db81db6cbd809d93cc3b7e7a088a351a3349c9fa02b6d393e35e0d51ba799")
CODEFORMER_VERSION = cfg("CODEFORMER_VERSION", "cc4956dd26fa5a7185d5660cc9100fab1b8070a1d1654a8bb5eb6d443b020bb2")
ESRGAN_VERSION = cfg("ESRGAN_VERSION", "b3ef194191d13140337468c916c2c5b96dd0cb06dffc032a022a31807f6a5ea8")
DDCOLOR_VERSION = cfg("DDCOLOR_VERSION", "ca494ba129e44e45f661d6ece83c4c98a9a7c774309beca01429b58fce8aa695")

MAX_BYTES = 5 * 1024 * 1024
app = FastAPI(title="heirloom-pipeline")


@app.on_event("startup")
def _warm() -> None:
    # Load AdaFace weights into VRAM while the cold-start clock is already
    # running, so the first /restore doesn't pay for it mid-pipeline.
    try:
        GATE.ready()
    except Exception as e:  # never block startup on a model load hiccup
        print(f"AdaFace warm failed (will retry on first use): {e}", flush=True)


@app.get("/health")
def health() -> Dict[str, bool]:
    return {"ok": True}


def _data_url(b: bytes, mime: str = "image/jpeg") -> str:
    return f"data:{mime};base64," + base64.b64encode(b).decode("ascii")


async def _download(url: str) -> bytes:
    async with httpx.AsyncClient(timeout=60.0) as client:
        r = await client.get(url)
        r.raise_for_status()
        return r.content


async def pipeline_events(image_bytes: bytes, token: str) -> AsyncGenerator[Dict[str, Any], None]:
    start = time.monotonic()
    def t() -> int:
        return int((time.monotonic() - start) * 1000)

    threshold = float(cfg("IDENTITY_THRESHOLD", "0.6"))
    fidelity = float(cfg("CODEFORMER_FIDELITY", "0.7"))
    gray_threshold = float(cfg("GRAYSCALE_THRESHOLD", "0.05"))
    input_data_url = _data_url(image_bytes)

    # auto_level (delegated to client pre-processing for v1; kept for contract parity)
    yield {"kind": "stage_start", "stage": "auto_level", "t_ms": t()}
    yield {"kind": "stage_done", "stage": "auto_level", "t_ms": t(),
           "extra": {"note": "Delegated to client pre-processing."}}

    # Stage 1: Bringing-Old-Photos-Back-to-Life (scratch/tear repair)
    yield {"kind": "stage_start", "stage": "bopb", "t_ms": t()}
    s = time.monotonic()
    bopb_url = as_url(await run_replicate(token, BOPB_VERSION,
                                          {"image": input_data_url, "with_scratch": True, "HR": False}))
    yield {"kind": "stage_done", "stage": "bopb", "t_ms": t(),
           "output_url": bopb_url, "extra": {"duration_ms": int((time.monotonic() - s) * 1000)}}

    # Stage 2: CodeFormer (face restoration)
    yield {"kind": "stage_start", "stage": "codeformer", "t_ms": t()}
    s = time.monotonic()
    codeformer_url = as_url(await run_replicate(token, CODEFORMER_VERSION,
                                                {"image": bopb_url, "codeformer_fidelity": fidelity,
                                                 "background_enhance": True, "face_upsample": True, "upscale": 2}))
    yield {"kind": "stage_done", "stage": "codeformer", "t_ms": t(),
           "output_url": codeformer_url, "extra": {"duration_ms": int((time.monotonic() - s) * 1000), "fidelity": fidelity}}

    # Stage 3: Real-ESRGAN (upscale + denoise)
    yield {"kind": "stage_start", "stage": "esrgan", "t_ms": t()}
    s = time.monotonic()
    restored_url = as_url(await run_replicate(token, ESRGAN_VERSION,
                                              {"image": codeformer_url, "scale": 2, "face_enhance": False}))
    yield {"kind": "stage_done", "stage": "esrgan", "t_ms": t(),
           "output_url": restored_url, "extra": {"duration_ms": int((time.monotonic() - s) * 1000)}}

    # Stage 4: AdaFace identity gate (LOCAL — original vs restored)
    yield {"kind": "stage_start", "stage": "adaface", "t_ms": t()}
    s = time.monotonic()
    cosine: Optional[float] = None
    identity_warning = False
    try:
        restored_bytes = await _download(restored_url)
        with tempfile.TemporaryDirectory() as d:
            a, b = os.path.join(d, "orig.jpg"), os.path.join(d, "restored.jpg")
            with open(a, "wb") as f: f.write(image_bytes)
            with open(b, "wb") as f: f.write(restored_bytes)
            cosine = GATE.compare(a, b)
        identity_warning = cosine is None or cosine < threshold
        yield {"kind": "stage_done", "stage": "adaface", "t_ms": t(),
               "extra": {"duration_ms": int((time.monotonic() - s) * 1000),
                         "cosine_similarity": cosine, "threshold": threshold,
                         "identity_warning": identity_warning}}
    except Exception as e:
        cosine = 0.0
        identity_warning = True
        yield {"kind": "stage_done", "stage": "adaface", "t_ms": t(),
               "extra": {"duration_ms": int((time.monotonic() - s) * 1000),
                         "error": str(e), "identity_warning": True}}

    # Stage 5: DDColor (B&W only)
    yield {"kind": "stage_start", "stage": "colorize_check", "t_ms": t()}
    input_is_gray = is_grayscale(image_bytes, gray_threshold)
    yield {"kind": "stage_done", "stage": "colorize_check", "t_ms": t(),
           "extra": {"is_grayscale": input_is_gray, "threshold": gray_threshold}}

    final_url = restored_url
    was_colorized = False
    if input_is_gray:
        yield {"kind": "stage_start", "stage": "ddcolor", "t_ms": t()}
        s = time.monotonic()
        final_url = as_url(await run_replicate(token, DDCOLOR_VERSION,
                                               {"image": restored_url, "model_size": "large"}))
        was_colorized = True
        yield {"kind": "stage_done", "stage": "ddcolor", "t_ms": t(),
               "output_url": final_url, "extra": {"duration_ms": int((time.monotonic() - s) * 1000)}}
    else:
        yield {"kind": "stage_skipped", "stage": "ddcolor", "t_ms": t(),
               "reason": "Input is color; colorization skipped."}

    # grain_synthesis (client-side RenderEffect for v1; kept for contract parity)
    yield {"kind": "stage_start", "stage": "grain_synthesis", "t_ms": t()}
    yield {"kind": "stage_done", "stage": "grain_synthesis", "t_ms": t(),
           "extra": {"note": "Delegated to Android client RenderEffect for v1."}}

    yield {"kind": "final", "t_ms": t(), "result": {
        "restored_url": final_url,
        "cosine_similarity": cosine,
        "identity_warning": identity_warning,
        "was_colorized": was_colorized,
        "adaface_skipped": False,
    }}


async def _read_image(image: UploadFile) -> bytes:
    data = await image.read()
    if not data:
        raise ValueError("image is empty")
    if len(data) > MAX_BYTES:
        raise ValueError("image too large; max 5MB for v1")
    return data


@app.post("/restore")
async def restore(image: UploadFile = File(...)):
    token = os.getenv("REPLICATE_API_TOKEN")
    if not token:
        return JSONResponse({"error": "missing REPLICATE_API_TOKEN"}, status_code=500)
    try:
        data = await _read_image(image)
    except ValueError as e:
        return JSONResponse({"error": str(e)}, status_code=400)
    try:
        result = None
        async for ev in pipeline_events(data, token):
            if ev["kind"] == "final":
                result = ev["result"]
            elif ev["kind"] == "error":
                return JSONResponse({"error": ev["message"]}, status_code=500)
        if result is None:
            return JSONResponse({"error": "pipeline produced no result"}, status_code=500)
        return JSONResponse(result)
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


@app.post("/restore-stream")
async def restore_stream(image: UploadFile = File(...)):
    token = os.getenv("REPLICATE_API_TOKEN")
    if not token:
        return JSONResponse({"error": "missing REPLICATE_API_TOKEN"}, status_code=500)
    data = await _read_image(image)

    import json as _json

    async def gen():
        try:
            async for ev in pipeline_events(data, token):
                yield _json.dumps(ev) + "\n"
        except Exception as e:
            yield _json.dumps({"kind": "error", "t_ms": 0, "message": str(e)}) + "\n"

    return StreamingResponse(gen(), media_type="application/x-ndjson",
                             headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})
