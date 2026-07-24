"""
Local model stages. Each stage exposes `run(...)` and lazy-loads its weights
into VRAM on first use (kept resident for the life of the container instance,
which scales to zero when idle). Images pass between stages as BGR uint8
numpy arrays (OpenCV convention); the orchestrator handles JPEG <-> array and
delivers the final image to the caller without persisting anything.

Runtime layout:
  esrgan      in-process
  adaface     in-process (app/adaface.py)
  codeformer  vendored repo + isolated venv
  ddcolor     vendored repo + isolated venv
  bopb        vendored repo + isolated venv
"""
