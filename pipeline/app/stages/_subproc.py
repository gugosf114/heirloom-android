"""Run a vendored model's own CLI in its isolated venv, raise on failure.

Each conflicting model (CodeFormer, DDColor, BOPB) lives in /opt/<repo> with its
own virtualenv, so their pinned/old deps never collide with the main app or each
other. Stages shell out to <venv>/bin/python <repo entrypoint>.
"""

import subprocess


def run_cmd(cmd, cwd=None, timeout=600, env=None):
    print("RUN:", " ".join(map(str, cmd)), flush=True)
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout, env=env)
    if p.returncode != 0:
        tail = (p.stderr or p.stdout or "")[-2000:]
        raise RuntimeError(f"{cmd[0]} failed (exit {p.returncode}): {tail}")
    return p.stdout
