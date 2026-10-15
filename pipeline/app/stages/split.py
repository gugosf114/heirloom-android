"""Find every individual photograph on a scanned album page (Gemini, JSON boxes).

Returns normalized boxes (0-1000, [ymin, xmin, ymax, xmax]) so the phone can crop
the full-resolution scan itself. Cheap: one text+image call, no credit.
"""

import base64
import json
import os
import urllib.request
from typing import Any, Dict, List

GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
PROMPT = (
    "This is a scan of an album page, a table, or a drawer with one or more separate printed "
    "photographs on it. Find every individual photograph. Return JSON only: "
    '{"photos":[{"box_2d":[ymin,xmin,ymax,xmax],"label":"short description"}]} '
    "with coordinates normalized to 0-1000. Include the whole print including its white border "
    "or film edge. Do not include the page background, captions, or stickers. "
    "If there is only one photograph, return one box."
)


def request_body(image_bytes: bytes) -> Dict[str, Any]:
    return {
        "contents": [{"parts": [
            {"text": PROMPT},
            {"inline_data": {"mime_type": "image/jpeg", "data": base64.b64encode(image_bytes).decode("ascii")}},
        ]}],
        "generationConfig": {"responseMimeType": "application/json"},
    }


def parse_boxes(text: str) -> List[Dict[str, Any]]:
    """Pure: model JSON -> clean list of {box: [ymin,xmin,ymax,xmax] in 0-1000, label}."""
    data = json.loads(text)
    out: List[Dict[str, Any]] = []
    for item in data.get("photos") or []:
        box = item.get("box_2d") or []
        if len(box) != 4:
            continue
        ymin, xmin, ymax, xmax = (max(0, min(1000, int(round(float(v))))) for v in box)
        if ymax - ymin < 20 or xmax - xmin < 20:
            continue  # a sliver is a mistake, not a photo
        out.append({"box": [ymin, xmin, ymax, xmax], "label": str(item.get("label", ""))[:80]})
    return out


def run(image_bytes: bytes, api_key: str = "") -> List[Dict[str, Any]]:
    key = api_key or os.getenv("GEMINI_API_KEY", "")
    if not key:
        raise RuntimeError("gemini: no API key configured")
    model = os.getenv("SPLIT_MODEL", "gemini-3.8-flash")
    req = urllib.request.Request(
        GEMINI_URL.format(model=model), data=json.dumps(request_body(image_bytes)).encode(),
        method="POST", headers={"Content-Type": "application/json", "x-goog-api-key": key},
    )
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            reply = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"gemini split HTTP {e.code}: {e.read().decode('utf-8', 'ignore')[:200]}") from None
    if "error" in reply:
        raise RuntimeError(f"gemini: {reply['error'].get('message', 'error')}")
    text = reply["candidates"][0]["content"]["parts"][0]["text"]
    return parse_boxes(text)
