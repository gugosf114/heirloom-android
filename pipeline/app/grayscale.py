"""
Cheap B&W detector (ported from worker/src/saturation.ts).

Sample ~4096 pixels, return mean color spread (max(r,g,b)-min(r,g,b), /255).
A truly grayscale photo sits near 0; the 0.05 default deliberately treats
faded sepia as "color" (it carries hue) so DDColor doesn't muddy it.
Decode failure -> treat as color (skip colorization) — safe failure mode.
"""

import io

import numpy as np
from PIL import Image


def mean_color_spread(image_bytes: bytes, sample_count: int = 4096) -> float:
    img = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    arr = np.asarray(img, dtype=np.int16).reshape(-1, 3)
    total = arr.shape[0]
    if total == 0:
        return 1.0
    stride = max(1, total // sample_count)
    sample = arr[::stride]
    spread = (sample.max(axis=1) - sample.min(axis=1)).astype(np.float32) / 255.0
    return float(spread.mean())


def is_grayscale(image_bytes: bytes, threshold: float) -> bool:
    try:
        return mean_color_spread(image_bytes) < threshold
    except Exception:
        return False
