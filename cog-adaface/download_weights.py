"""
Build-time weights download for the AdaFace cog.

Pulls the full minchul/cvlface_adaface_ir101_webface4m HuggingFace repo
into /src/adaface_weights/. The author's wrapper.py uses relative paths,
so we mirror the directory structure verbatim and chdir into it from
predict.py at setup time.

This runs once at `cog build`, never at predict time. The image carries
the weights baked in (~250 MB).
"""

import os
import sys

from huggingface_hub import hf_hub_download

REPO_ID = "minchul/cvlface_adaface_ir101_webface4m"
TARGET_DIR = "/src/adaface_weights"

# Files explicitly required by the wrapper at load time. The repo also has
# files.txt which lists the full file manifest, so we read that and
# deduplicate against this base set.
REQUIRED_FILES = {
    "config.json",
    "wrapper.py",
    "model.safetensors",
    "files.txt",
}


def main() -> None:
    os.makedirs(TARGET_DIR, exist_ok=True)
    print(f"Downloading {REPO_ID} -> {TARGET_DIR}", flush=True)

    files_txt = hf_hub_download(
        REPO_ID,
        "files.txt",
        local_dir=TARGET_DIR,
        local_dir_use_symlinks=False,
    )
    with open(files_txt, "r", encoding="utf-8") as f:
        manifest = {line.strip() for line in f if line.strip()}

    all_files = sorted(manifest | REQUIRED_FILES)
    print(f"Pulling {len(all_files)} files", flush=True)
    for fname in all_files:
        try:
            hf_hub_download(
                REPO_ID,
                fname,
                local_dir=TARGET_DIR,
                local_dir_use_symlinks=False,
            )
        except Exception as e:
            # Don't tolerate missing required files — model won't load.
            if fname in REQUIRED_FILES:
                print(f"FATAL: required file {fname} missing: {e}", file=sys.stderr)
                raise
            print(f"warn: optional file {fname} skipped: {e}", flush=True)

    # Sanity check: required files actually present.
    for fname in REQUIRED_FILES:
        full_path = os.path.join(TARGET_DIR, fname)
        if not os.path.exists(full_path):
            raise SystemExit(f"FATAL: {fname} missing after download")

    weights_path = os.path.join(TARGET_DIR, "model.safetensors")
    size_mb = os.path.getsize(weights_path) / (1024 * 1024)
    print(f"OK: model.safetensors {size_mb:.1f} MB", flush=True)


if __name__ == "__main__":
    main()
