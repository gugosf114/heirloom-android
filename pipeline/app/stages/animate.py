"""Bring a restored photograph gently to life: one Veo image-to-video call.

POST .../models/{model}:predictLongRunning with the photo, then poll the
operation until done. Returns MP4 bytes. Standard library only.
"""

import base64
import json
import os
import time
import urllib.request
from typing import Any, Dict, Optional

VEO_BASE = "https://generativelanguage.googleapis.com/v1beta"
POLL_SECONDS = float(os.getenv("VEO_POLL_SECONDS", "6"))
TIMEOUT_SECONDS = int(os.getenv("VEO_TIMEOUT_SECONDS", "300"))

ANIMATION_PROMPT = (
    "The people in this old photograph come gently to life: subtle natural breathing, "
    "a soft blink, a slight turn of the head, a faint smile. Keep the exact faces, clothes, "
    "framing, and the original film look. Slow, calm, no camera movement, no new objects."
)


def request_body(image_bytes: bytes, seconds: int, prompt: str = ANIMATION_PROMPT) -> Dict[str, Any]:
    return {
        "instances": [{
            "prompt": prompt,
            "image": {"bytesBase64Encoded": base64.b64encode(image_bytes).decode("ascii"), "mimeType": "image/jpeg"},
        }],
        "parameters": {"durationSeconds": seconds, "resolution": "720p", "personGeneration": "allow_all"},
    }


def video_from_operation(op: Dict[str, Any]) -> Dict[str, Any]:
    """Pure: pick the finished video out of a done operation, or raise with the reason."""
    if "error" in op:
        raise RuntimeError(f"veo: {op['error'].get('message', 'error')}")
    resp = op.get("response") or {}
    gv = resp.get("generateVideoResponse") or {}
    samples = gv.get("generatedSamples") or resp.get("generatedSamples") or []
    if not samples:
        reasons = gv.get("raiMediaFilteredReasons") or []
        if reasons:
            raise RuntimeError("veo refused this photo: " + str(reasons[0])[:200])
        raise RuntimeError("veo: no video in reply")
    return samples[0].get("video") or {}


def _get_json(url: str, key: str) -> Dict[str, Any]:
    req = urllib.request.Request(url, headers={"x-goog-api-key": key})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def run(in_path: str, out_dir: str, seconds: int = 4, api_key: Optional[str] = None) -> str:
    key = api_key or os.getenv("GEMINI_API_KEY", "")
    if not key:
        raise RuntimeError("veo: no API key configured")
    model = os.getenv("VEO_MODEL", "veo-3.1-lite-generate-preview")
    seconds = max(4, min(8, int(seconds)))
    with open(in_path, "rb") as f:
        image_bytes = f.read()
    req = urllib.request.Request(
        f"{VEO_BASE}/models/{model}:predictLongRunning",
        data=json.dumps(request_body(image_bytes, seconds)).encode(), method="POST",
        headers={"Content-Type": "application/json", "x-goog-api-key": key},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            op = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"veo start HTTP {e.code}: {e.read().decode('utf-8', 'ignore')[:200]}") from None
    name = op.get("name")
    if not name:
        raise RuntimeError("veo: no operation name")
    start = time.monotonic()
    while True:
        time.sleep(POLL_SECONDS)
        op = _get_json(f"{VEO_BASE}/{name}", key)
        if op.get("done"):
            break
        if time.monotonic() - start > TIMEOUT_SECONDS:
            raise RuntimeError("veo: timed out")
    video = video_from_operation(op)
    if video.get("uri"):
        with urllib.request.urlopen(urllib.request.Request(video["uri"], headers={"x-goog-api-key": key}), timeout=180) as r:
            data = r.read()
    else:
        data = base64.b64decode(video.get("bytesBase64Encoded", ""))
    if len(data) < 10_000:
        raise RuntimeError("veo: video implausibly small")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "animation.mp4")
    with open(out, "wb") as f:
        f.write(data)
    return out
