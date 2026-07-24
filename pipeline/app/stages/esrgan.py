"""Real-ESRGAN x2 upscale + denoise — local port of the `esrgan` stage."""

import os
from typing import Optional, Tuple

import numpy as np

_UPSAMPLER = None


def _upsampler():
    global _UPSAMPLER
    if _UPSAMPLER is None:
        import torch
        from basicsr.archs.rrdbnet_arch import RRDBNet
        from realesrgan import RealESRGANer

        model = RRDBNet(num_in_ch=3, num_out_ch=3, num_feat=64, num_block=23, num_grow_ch=32, scale=2)
        weights = os.getenv("ESRGAN_WEIGHTS", "/opt/weights/RealESRGAN_x2plus.pth")
        cuda = torch.cuda.is_available()
        _UPSAMPLER = RealESRGANer(
            scale=2,
            model_path=weights,
            model=model,
            half=cuda,
            device="cuda" if cuda else "cpu",
        )
    return _UPSAMPLER


def run(img_bgr: np.ndarray, outscale: int = 2) -> np.ndarray:
    """img_bgr: HxWx3 uint8 BGR -> upscaled HxWx3 uint8 BGR."""
    out, _ = _upsampler().enhance(img_bgr, outscale=outscale)
    return out
