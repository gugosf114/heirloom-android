"""CodeFormer face restoration (stage 2) — vendored repo in its own venv.

Mirrors the proven Replicate params: codeformer_fidelity=0.7 (-w), upscale=2 (-s),
background_enhance=true (--bg_upsampler realesrgan), face_upsample=true.
"""

import glob
import os

from ._subproc import run_cmd

CODEFORMER_DIR = os.getenv("CODEFORMER_DIR", "/opt/CodeFormer")
CODEFORMER_PY = os.getenv("CODEFORMER_PY", "/opt/CodeFormer/venv/bin/python")


def run(in_path: str, out_dir: str, fidelity: float = 0.7, upscale: int = 2) -> str:
    os.makedirs(out_dir, exist_ok=True)
    run_cmd(
        [
            CODEFORMER_PY, "inference_codeformer.py",
            "-w", str(fidelity),
            "-s", str(upscale),
            "--bg_upsampler", "realesrgan",
            "--face_upsample",
            "--input_path", in_path,
            "--output_path", out_dir,
        ],
        cwd=CODEFORMER_DIR,
    )
    # inference_codeformer.py writes the full restored image to final_results/.
    hits = glob.glob(os.path.join(out_dir, "final_results", "*"))
    if not hits:
        hits = glob.glob(os.path.join(out_dir, "**", "*.png"), recursive=True)
    if not hits:
        raise RuntimeError(f"CodeFormer produced no output under {out_dir}")
    return sorted(hits)[0]
