"""
Heirloom restoration pipeline — Cloud Run (L4, scale-to-zero), fully local.

No Replicate. All 5 models run in-container; the finished image is streamed
back as a data URL (nothing is persisted — keeps the "processed and discarded"
privacy promise).

  GET  /health
  POST /restore         multipart { image } -> single JSON PipelineResult
  POST /restore-stream  multipart { image } -> NDJSON stream of stage events

Stage events and PipelineResult fields match worker/src/index.ts byte-for-byte,
except `restored_url` is now a `data:image/jpeg;base64,...` URL instead of a
remote link. The Android app treats it as an image source either way.

Stages run synchronously (subprocess + torch); the endpoints are sync `def`,
so FastAPI runs them in its threadpool and the event loop is never blocked.
"""

import base64
import io
import json
import os
import shutil
import tempfile
import time
from typing import Any, Dict, Generator, Optional

import cv2
from PIL import Image
from fastapi import FastAPI, UploadFile, File
from fastapi.responses import JSONResponse, StreamingResponse

from .adaface import GATE
from .grayscale import is_grayscale
from .stages import bopb, codeformer, ddcolor, esrgan

MAX_BYTES = 5 * 1024 * 1024
app = FastAPI(title="heirloom-pipeline")


@app.on_event("startup")
def _warm() -> None:
    try:
        GATE.ready()
    except Exception as e:
        print(f"AdaFace warm failed (will retry on first use): {e}", flush=True)


@app.get("/health")
def health() -> Dict[str, bool]:
    return {"ok": True}


def _encode_jpeg(path: str) -> bytes:
    img = Image.open(path).convert("RGB")
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=92)
    return buf.getvalue()


def _data_url(jpeg_bytes: bytes) -> str:
    return "data:image/jpeg;base64," + base64.b64encode(jpeg_bytes).decode("ascii")


def pipeline_events(image_bytes: bytes, workdir: str) -> Generator[Dict[str, Any], None, None]:
    start = time.monotonic()
    def t() -> int:
        return int((time.monotonic() - start) * 1000)

    threshold = float(os.getenv("IDENTITY_THRESHOLD", "0.6"))
    fidelity = float(os.getenv("CODEFORMER_FIDELITY", "0.7"))
    gray_threshold = float(os.getenv("GRAYSCALE_THRESHOLD", "0.05"))

    in_dir = os.path.join(workdir, "in")
    os.makedirs(in_dir, exist_ok=True)
    orig_path = os.path.join(in_dir, "orig.jpg")
    with open(orig_path, "wb") as f:
        f.write(image_bytes)

    yield {"kind": "stage_start", "stage": "auto_level", "t_ms": t()}
    yield {"kind": "stage_done", "stage": "auto_level", "t_ms": t(),
           "extra": {"note": "normalize handled inline by downstream stages"}}

    # Working image flows through each stage. A stage failure is logged in its
    # event and the previous image passes through — best-effort, a single bad
    # model never kills the whole restore.
    cur = orig_path

    # Stage 1: BOPB (scratch/tear repair) — folder in, folder out.
    yield {"kind": "stage_start", "stage": "bopb", "t_ms": t()}
    s = time.monotonic()
    try:
        cur = bopb.run(in_dir, os.path.join(workdir, "bopb"))
        yield {"kind": "stage_done", "stage": "bopb", "t_ms": t(),
               "extra": {"duration_ms": int((time.monotonic() - s) * 1000)}}
    except Exception as e:
        yield {"kind": "stage_done", "stage": "bopb", "t_ms": t(),
               "extra": {"duration_ms": int((time.monotonic() - s) * 1000),
                         "skipped": True, "error": str(e)[:1500]}}

    # Stage 2: CodeFormer (face restoration).
    yield {"kind": "stage_start", "stage": "codeformer", "t_ms": t()}
    s = time.monotonic()
    try:
        cur = codeformer.run(cur, os.path.join(workdir, "cf"), fidelity=fidelity, upscale=2)
        yield {"kind": "stage_done", "stage": "codeformer", "t_ms": t(),
               "extra": {"duration_ms": int((time.monotonic() - s) * 1000), "fidelity": fidelity}}
    except Exception as e:
        yield {"kind": "stage_done", "stage": "codeformer", "t_ms": t(),
               "extra": {"duration_ms": int((time.monotonic() - s) * 1000),
                         "skipped": True, "error": str(e)[:1500]}}

    # Stage 3: Real-ESRGAN (in-process upscale + denoise).
    yield {"kind": "stage_start", "stage": "esrgan", "t_ms": t()}
    s = time.monotonic()
    try:
        restored_bgr = esrgan.run(cv2.imread(cur, cv2.IMREAD_COLOR), outscale=2)
        restored_path = os.path.join(workdir, "restored.png")
        cv2.imwrite(restored_path, restored_bgr)
        cur = restored_path
        yield {"kind": "stage_done", "stage": "esrgan", "t_ms": t(),
               "extra": {"duration_ms": int((time.monotonic() - s) * 1000)}}
    except Exception as e:
        yield {"kind": "stage_done", "stage": "esrgan", "t_ms": t(),
               "extra": {"duration_ms": int((time.monotonic() - s) * 1000),
                         "skipped": True, "error": str(e)[:1500]}}

    # Stage 4: AdaFace identity gate (local: original vs current restored).
    yield {"kind": "stage_start", "stage": "adaface", "t_ms": t()}
    s = time.monotonic()
    cosine: Optional[float] = None
    identity_warning = False
    try:
        cosine, det = GATE.compare(orig_path, cur)
        identity_warning = cosine is None or cosine < threshold
        yield {"kind": "stage_done", "stage": "adaface", "t_ms": t(),
               "extra": {"duration_ms": int((time.monotonic() - s) * 1000),
                         "cosine_similarity": cosine, "threshold": threshold,
                         "identity_warning": identity_warning, **det}}
    except Exception as e:
        cosine, identity_warning = 0.0, True
        yield {"kind": "stage_done", "stage": "adaface", "t_ms": t(),
               "extra": {"duration_ms": int((time.monotonic() - s) * 1000),
                         "error": str(e)[:1500], "identity_warning": True}}

    # Stage 5: DDColor (B&W input only).
    yield {"kind": "stage_start", "stage": "colorize_check", "t_ms": t()}
    input_is_gray = is_grayscale(image_bytes, gray_threshold)
    yield {"kind": "stage_done", "stage": "colorize_check", "t_ms": t(),
           "extra": {"is_grayscale": input_is_gray, "threshold": gray_threshold}}

    was_colorized = False
    if input_is_gray:
        yield {"kind": "stage_start", "stage": "ddcolor", "t_ms": t()}
        s = time.monotonic()
        try:
            dd_in = os.path.join(workdir, "dd_in")
            os.makedirs(dd_in, exist_ok=True)
            shutil.copy(cur, os.path.join(dd_in, "img.png"))
            cur = ddcolor.run(dd_in, os.path.join(workdir, "dd_out"))
            was_colorized = True
            yield {"kind": "stage_done", "stage": "ddcolor", "t_ms": t(),
                   "extra": {"duration_ms": int((time.monotonic() - s) * 1000)}}
        except Exception as e:
            yield {"kind": "stage_done", "stage": "ddcolor", "t_ms": t(),
                   "extra": {"duration_ms": int((time.monotonic() - s) * 1000),
                             "skipped": True, "error": str(e)[:1500]}}
    else:
        yield {"kind": "stage_skipped", "stage": "ddcolor", "t_ms": t(),
               "reason": "Input is color; colorization skipped."}

    yield {"kind": "stage_start", "stage": "grain_synthesis", "t_ms": t()}
    yield {"kind": "stage_done", "stage": "grain_synthesis", "t_ms": t(),
           "extra": {"note": "Delegated to Android client RenderEffect for v1."}}

    yield {"kind": "final", "t_ms": t(), "result": {
        "restored_url": _data_url(_encode_jpeg(cur)),
        "cosine_similarity": cosine,
        "identity_warning": identity_warning,
        "was_colorized": was_colorized,
        "adaface_skipped": False,
    }}


def _read_image(image: UploadFile) -> bytes:
    data = image.file.read()
    if not data:
        raise ValueError("image is empty")
    if len(data) > MAX_BYTES:
        raise ValueError("image too large; max 5MB for v1")
    return data


@app.post("/restore")
def restore(image: UploadFile = File(...)):
    try:
        data = _read_image(image)
    except ValueError as e:
        return JSONResponse({"error": str(e)}, status_code=400)
    try:
        with tempfile.TemporaryDirectory() as workdir:
            result = None
            for ev in pipeline_events(data, workdir):
                if ev["kind"] == "final":
                    result = ev["result"]
        if result is None:
            return JSONResponse({"error": "pipeline produced no result"}, status_code=500)
        return JSONResponse(result)
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


@app.post("/restore-stream")
def restore_stream(image: UploadFile = File(...)):
    data = _read_image(image)

    def gen():
        try:
            with tempfile.TemporaryDirectory() as workdir:
                for ev in pipeline_events(data, workdir):
                    yield json.dumps(ev) + "\n"
        except Exception as e:
            yield json.dumps({"kind": "error", "t_ms": 0, "message": str(e)}) + "\n"

    return StreamingResponse(gen(), media_type="application/x-ndjson",
                             headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})
