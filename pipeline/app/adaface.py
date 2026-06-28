"""
Heirloom AdaFace identity gate — local in-container version.

Ported from cog-adaface/predict.py (which ran on the always-warm Replicate
L40S). Here it runs locally on the Cloud Run L4, so the warm box — and its
24/7 bill — goes away. Lazy-loaded once per container instance; the model
stays resident for the life of the instance and the instance scales to zero
when idle.

compare(path_a, path_b) -> cosine in [-1, 1], or None when either image has
no detectable face. The orchestrator treats None as an identity warning —
silent passes are the worst outcome.
"""

import os
import sys
from typing import Optional, Tuple

import numpy as np
import torch
from PIL import Image
from facenet_pytorch import MTCNN

from .face_align import align_face_5pt

WEIGHTS_DIR = os.getenv("ADAFACE_WEIGHTS_DIR", "/opt/adaface_weights")
ADAFACE_INPUT_SIZE = 112


class AdaFaceGate:
    """Lazy singleton. Call .ready() once at startup (or on first use)."""

    def __init__(self) -> None:
        self.model = None
        self.mtcnn = None
        self.device = "cpu"

    def ready(self) -> "AdaFaceGate":
        if self.model is not None:
            return self
        force_cpu = bool(os.getenv("USE_CPU"))
        self.device = "cpu" if force_cpu or not torch.cuda.is_available() else "cuda"
        print(f"AdaFace setup: device={self.device}", flush=True)

        # The HF wrapper.py opens 'pretrained_model/model.yaml' with a relative
        # path, so cwd must be WEIGHTS_DIR at AutoModel.from_pretrained time.
        from transformers import AutoModel
        cwd = os.getcwd()
        os.chdir(WEIGHTS_DIR)
        if WEIGHTS_DIR not in sys.path:
            sys.path.insert(0, WEIGHTS_DIR)
        try:
            self.model = (
                AutoModel.from_pretrained(WEIGHTS_DIR, trust_remote_code=True)
                .to(self.device)
                .eval()
            )
        finally:
            os.chdir(cwd)

        self.mtcnn = MTCNN(
            image_size=ADAFACE_INPUT_SIZE,
            margin=0,
            post_process=False,
            select_largest=True,
            device=self.device,
            keep_all=False,
        )
        print("AdaFace setup: ready", flush=True)
        return self

    def compare(self, path_a: str, path_b: str):
        """Returns (cosine_or_None, info_dict)."""
        self.ready()
        emb1, c1 = self._embed(path_a)
        emb2, c2 = self._embed(path_b)
        info = {"det_orig": round(c1, 3), "det_restored": round(c2, 3),
                "face_orig": emb1 is not None, "face_restored": emb2 is not None}
        if emb1 is None or emb2 is None:
            return None, info
        n1 = float(np.linalg.norm(emb1))
        n2 = float(np.linalg.norm(emb2))
        if n1 < 1e-8 or n2 < 1e-8:
            return None, info
        return float(np.dot(emb1, emb2) / (n1 * n2)), info

    def _embed(self, image_path: str) -> Tuple[Optional[np.ndarray], float]:
        img = Image.open(image_path).convert("RGB")
        np_img = np.array(img)

        boxes, probs, landmarks = self.mtcnn.detect(img, landmarks=True)
        if boxes is None or len(boxes) == 0:
            return None, 0.0
        if probs is None or probs[0] is None:
            return None, 0.0

        landmark = np.asarray(landmarks[0], dtype=np.float32)
        confidence = float(probs[0])
        try:
            aligned_rgb = align_face_5pt(np_img, landmark)
        except ValueError as e:
            print(f"alignment failed: {e}", flush=True)
            return None, confidence

        tensor = torch.from_numpy(aligned_rgb).permute(2, 0, 1).float() / 255.0
        tensor = (tensor - 0.5) / 0.5
        tensor = tensor.unsqueeze(0).to(self.device)
        with torch.no_grad():
            output = self.model(tensor)
        features = output[0] if isinstance(output, (tuple, list)) else output
        return features.detach().cpu().numpy().squeeze().astype(np.float32), confidence


# Module-level singleton.
GATE = AdaFaceGate()
