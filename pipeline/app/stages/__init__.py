"""
Local model stages. Each stage exposes `run(...)` and lazy-loads its weights
into VRAM on first use (kept resident for the life of the container instance,
which scales to zero when idle). Images pass between stages as BGR uint8
numpy arrays (OpenCV convention); the orchestrator handles JPEG <-> array and
delivers the final image to the caller without persisting anything.

Port status (-> fully Replicate-free pipeline):
  esrgan      DONE (in-process)
  adaface     DONE (app/adaface.py, in-process)
  codeformer  PORTED (vendored repo + own venv; pending first-build verification)
  ddcolor     PORTED (vendored repo + own venv; pending first-build verification)
  bopb        PORTED (vendored repo + own venv; pending first-build verification)
  -- server.py is now fully local (no Replicate). Next: first Cloud Build + smoke.
"""
