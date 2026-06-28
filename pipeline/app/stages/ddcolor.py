"""DDColor colorization (stage 5, B&W input only) — vendored repo in its own venv.

The Replicate model used model_size=large; we pin the full (non-tiny) checkpoint
ddcolor_modelscope.pth. Only invoked when the orchestrator detects a B&W input.
"""

import glob
import os

from ._subproc import run_cmd

DDCOLOR_DIR = os.getenv("DDCOLOR_DIR", "/opt/DDColor")
DDCOLOR_PY = os.getenv("DDCOLOR_PY", "/opt/DDColor/venv/bin/python")
DDCOLOR_WEIGHTS = os.getenv("DDCOLOR_WEIGHTS", "/opt/weights/ddcolor_modelscope.pth")


def run(in_dir: str, out_dir: str) -> str:
    """in_dir: directory holding the single input image; out_dir: results dir."""
    os.makedirs(out_dir, exist_ok=True)
    run_cmd(
        [
            DDCOLOR_PY, "scripts/infer.py",
            "--model_path", DDCOLOR_WEIGHTS,
            "--model_size", "large",
            "--input", in_dir,
            "--output", out_dir,
        ],
        cwd=DDCOLOR_DIR,
        env={**os.environ, "PYTHONPATH": DDCOLOR_DIR},
    )
    hits = [f for f in glob.glob(os.path.join(out_dir, "*")) if not f.endswith(".txt")]
    if not hits:
        raise RuntimeError(f"DDColor produced no output under {out_dir}")
    return sorted(hits)[0]
