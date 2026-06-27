"""Bringing-Old-Photos-Back-to-Life (stage 1, scratch/tear repair).

Microsoft's repo runs as its own multi-stage pipeline (global restoration +
scratch detection + face enhancement) via run.py over an input *folder*. We
vendor it in an isolated venv and shell out, mirroring the proven Replicate
call (with_scratch=true). The boss-fight stage: old deps (dlib, sync-batchnorm),
expect first-build iteration.
"""

import glob
import os

from ._subproc import run_cmd

BOPB_DIR = os.getenv("BOPB_DIR", "/opt/BOPB")
BOPB_PY = os.getenv("BOPB_PY", "/opt/BOPB/venv/bin/python")


def run(in_dir: str, out_dir: str) -> str:
    """in_dir: folder holding the single input image; returns final output path."""
    os.makedirs(out_dir, exist_ok=True)
    out = run_cmd(
        [
            BOPB_PY, "run.py",
            "--input_folder", in_dir,
            "--output_folder", out_dir,
            "--GPU", "0",
            "--with_scratch",
        ],
        cwd=BOPB_DIR,
        timeout=900,
    )
    # run.py writes the finished image to <out_dir>/final_output/.
    hits = glob.glob(os.path.join(out_dir, "final_output", "*"))
    if not hits:
        produced = [os.path.relpath(p, out_dir)
                    for p in glob.glob(os.path.join(out_dir, "**"), recursive=True)][:25]
        raise RuntimeError(
            f"BOPB no final_output. produced={produced} | log_tail={(out or '')[-900:]}"
        )
    return sorted(hits)[0]
