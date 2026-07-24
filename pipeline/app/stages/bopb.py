"""Bringing-Old-Photos-Back-to-Life (stage 1, scratch/tear repair).

Microsoft's repo runs as its own multi-stage pipeline (global restoration +
scratch detection + face enhancement) via run.py over an input *folder*. We
vendor it in an isolated venv and shell out with scratch repair enabled.
"""

import glob
import os

from ._subproc import run_cmd

BOPB_DIR = os.getenv("BOPB_DIR", "/opt/BOPB")
BOPB_PY = os.getenv("BOPB_PY", "/opt/BOPB/venv/bin/python")


def _invoke(in_dir: str, out_dir: str, with_scratch: bool) -> str:
    os.makedirs(out_dir, exist_ok=True)
    command = [
        BOPB_PY, "run.py",
        "--input_folder", in_dir,
        "--output_folder", out_dir,
        "--GPU", "0",
    ]
    if with_scratch:
        command.append("--with_scratch")
    return run_cmd(
        command,
        cwd=BOPB_DIR,
        timeout=900,
    )


def _find_output(out_dir: str) -> str | None:
    # We want BOPB's Stage-1 global restoration (scratch/tear repair), which lands in
    # stage_1_restore_output/restored_image/. final_output is the face-blended result
    # (redundant with CodeFormer downstream) and is often empty.
    candidates = (
        glob.glob(
            os.path.join(out_dir, "stage_1_restore_output", "restored_image", "**", "*"),
            recursive=True,
        )
        or glob.glob(os.path.join(out_dir, "final_output", "**", "*"), recursive=True)
    )
    hits = [path for path in candidates if os.path.isfile(path)]
    return sorted(hits)[0] if hits else None


def run(in_dir: str, out_dir: str) -> str:
    """in_dir: folder holding the single input image; returns final output path."""
    scratch_log = _invoke(in_dir, out_dir, with_scratch=True)
    result = _find_output(out_dir)
    if result:
        return result

    # BOPB sometimes exits successfully but silently emits no image from its
    # scratch-mask path. Retry the global restoration path in a clean folder so
    # a recoverable detector/model edge case cannot skip the whole stage.
    print("BOPB scratch pass produced no image; retrying without scratch mask", flush=True)
    fallback_dir = os.path.join(out_dir, "without_scratch")
    fallback_log = _invoke(in_dir, fallback_dir, with_scratch=False)
    result = _find_output(fallback_dir)
    if result:
        return result

    produced = [
        os.path.relpath(path, out_dir)
        for path in glob.glob(os.path.join(out_dir, "**"), recursive=True)
    ][:40]
    raise RuntimeError(
        "BOPB produced no restored image after scratch and fallback passes. "
        f"produced={produced} | scratch_log_tail={scratch_log[-600:]} | "
        f"fallback_log_tail={fallback_log[-600:]}"
    )
