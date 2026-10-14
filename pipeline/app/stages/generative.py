"""One-call generative restoration (replaces BOPB + CodeFormer + DDColor).

A single image-editing model repairs damage, rebuilds faces, and (only when
asked) adds color. Two providers, picked by env:

  RESTORE_PROVIDER=openai   POST https://api.openai.com/v1/images/edits   (default)
  RESTORE_PROVIDER=gemini   POST .../models/{model}:generateContent

Only the standard library is used: multipart is built by hand so the container
needs no new dependency. The functions that build requests and read replies are
pure so the tests cover them without a network.
"""

import base64
import json
import os
import urllib.request
import uuid
from typing import Any, Dict, Optional, Tuple

OPENAI_EDITS_URL = "https://api.openai.com/v1/images/edits"
GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
TIMEOUT_SECONDS = int(os.getenv("GENERATIVE_TIMEOUT_SECONDS", "240"))


MODES = ("full", "repair", "sharpen", "color")

IDENTITY_RULES = (
    "Every person must keep exactly the same identity, face shape, features, age, expression, "
    "hairstyle, and clothing. Do not beautify, slim, or change anyone. "
    "Keep the exact framing, composition, and everything in the scene; do not add, remove, or "
    "move anything. "
)


def restoration_prompt(colorize: bool, mode: str = "full") -> str:
    """The instruction every provider gets. Identity first, no inventions.

    full    damage repair + faces + detail (the default)
    repair  scratches, tears, stains, fading only; leave sharpness and faces as they are
    sharpen faces and fine detail only; leave damage marks alone
    color   add color only; change nothing else
    """
    mode = mode if mode in MODES else "full"
    add_color = (
        "The photograph is black and white or faded. Add realistic, natural, period-appropriate "
        "color to the whole image."
    )
    keep_tones = (
        "Keep the original tones exactly: if it is black and white, sepia, or faded, leave "
        "it black and white, sepia, or faded. Do not add color."
    )
    if mode == "color":
        return ("Colorize this photograph and change nothing else. " + add_color + " Do not repair, "
                "sharpen, smooth, or alter any detail; only the colors change. " + IDENTITY_RULES +
                "Return only the colorized photograph.")
    color = add_color if colorize else keep_tones
    if mode == "repair":
        work = ("Repair the physical damage on this old photograph only: remove scratches, tears, creases, "
                "dust, stains, mold spots, and fading, and fill missing or torn areas so they match their "
                "surroundings. Do not sharpen, smooth, or redraw faces or detail; leave the grain and "
                "softness exactly as they are. ")
    elif mode == "sharpen":
        work = ("Sharpen this old photograph only: bring faces and fine detail into clear focus and reduce "
                "blur and noise. Do not repair scratches, tears, stains, or other damage marks; leave them "
                "exactly where they are. ")
    else:
        work = ("Restore this old, damaged photograph. Remove scratches, tears, creases, dust, stains, "
                "mold spots, and fading. Repair missing or torn areas so they match their surroundings. "
                "Sharpen faces and fine detail. ")
    return work + IDENTITY_RULES + color + " Return only the restored photograph."


def provider() -> str:
    return os.getenv("RESTORE_PROVIDER", "openai").strip().lower()


# --- OpenAI ---------------------------------------------------------------

def openai_multipart(image_bytes: bytes, prompt: str, model: str, fidelity: str, quality: str) -> Tuple[bytes, str]:
    boundary = "heirloom-" + uuid.uuid4().hex
    fields = [("model", model), ("prompt", prompt), ("size", "auto"), ("n", "1"),
              ("output_format", "jpeg"), ("quality", quality)]
    if fidelity:
        fields.append(("input_fidelity", fidelity))
    parts = []
    for name, value in fields:
        parts.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode()
        )
    parts.append(
        (f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"input.jpg\"\r\n"
         "Content-Type: image/jpeg\r\n\r\n").encode() + image_bytes + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def extract_openai_image(reply: Dict[str, Any]) -> bytes:
    if "error" in reply:
        raise RuntimeError(f"openai: {reply['error'].get('message', 'error')}")
    data = reply.get("data") or []
    b64 = data[0].get("b64_json") if data else None
    if not b64:
        raise RuntimeError("openai: reply carried no image")
    return base64.b64decode(b64)


def _openai(image_bytes: bytes, prompt: str, api_key: str) -> bytes:
    model = os.getenv("OPENAI_IMAGE_MODEL", "gpt-image-1.5")
    fidelity = os.getenv("OPENAI_IMAGE_FIDELITY", "high")
    quality = os.getenv("OPENAI_IMAGE_QUALITY", "high")
    body, content_type = openai_multipart(image_bytes, prompt, model, fidelity, quality)
    req = urllib.request.Request(
        OPENAI_EDITS_URL, data=body, method="POST",
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": content_type},
    )
    return extract_openai_image(_post(req))


# --- Gemini ---------------------------------------------------------------

def gemini_body(image_bytes: bytes, prompt: str) -> Dict[str, Any]:
    return {
        "contents": [{"parts": [
            {"text": prompt},
            {"inline_data": {"mime_type": "image/jpeg", "data": base64.b64encode(image_bytes).decode("ascii")}},
        ]}],
        "generationConfig": {"responseModalities": ["IMAGE"]},
    }


def extract_gemini_image(reply: Dict[str, Any]) -> bytes:
    if "error" in reply:
        raise RuntimeError(f"gemini: {reply['error'].get('message', 'error')}")
    for candidate in reply.get("candidates") or []:
        for part in (candidate.get("content") or {}).get("parts") or []:
            inline = part.get("inlineData") or part.get("inline_data")
            if inline and inline.get("data"):
                return base64.b64decode(inline["data"])
    raise RuntimeError("gemini: reply carried no image")


def _gemini(image_bytes: bytes, prompt: str, api_key: str) -> bytes:
    model = os.getenv("GEMINI_IMAGE_MODEL", "gemini-2.5-flash-image")
    req = urllib.request.Request(
        GEMINI_URL.format(model=model), data=json.dumps(gemini_body(image_bytes, prompt)).encode(),
        method="POST", headers={"Content-Type": "application/json", "x-goog-api-key": api_key},
    )
    return extract_gemini_image(_post(req))


# --- shared ---------------------------------------------------------------

def _post(req: urllib.request.Request) -> Dict[str, Any]:
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode("utf-8"))
        except Exception:
            raise RuntimeError(f"generative provider HTTP {e.code}") from None


def run(in_path: str, out_dir: str, colorize: bool, api_key: Optional[str] = None, mode: str = "full") -> str:
    """Restore the photo at in_path; return the path of the restored JPEG."""
    name = provider()
    key = api_key or os.getenv("OPENAI_API_KEY" if name == "openai" else "GEMINI_API_KEY", "")
    if not key:
        raise RuntimeError(f"{name}: no API key configured")
    with open(in_path, "rb") as f:
        image_bytes = f.read()
    prompt = restoration_prompt(colorize, mode)
    restored = _openai(image_bytes, prompt, key) if name == "openai" else _gemini(image_bytes, prompt, key)
    if len(restored) < 1024:
        raise RuntimeError(f"{name}: restored image is implausibly small")
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "restored.jpg")
    with open(out_path, "wb") as f:
        f.write(restored)
    return out_path
