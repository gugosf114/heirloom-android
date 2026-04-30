"""
Heirloom AdaFace identity gate.

Inputs: two face images.
Output: cosine similarity in [-1, 1], plus per-image detection flags.

Pipeline per image:
  1. PIL load -> RGB ndarray
  2. facenet-pytorch MTCNN -> 5-point landmarks (top-1 face by area)
  3. ArcFace similarity-transform align -> 112x112 RGB
  4. Normalize to [-1, 1] (AdaFace convention)
  5. AdaFace IR-101 forward -> 512-d feature
  6. L2 normalize, cosine = <a, b>

Failure modes are surfaced explicitly: cosine_similarity is null when
either image lacks a detectable face. The Heirloom Worker treats null as
an identity-warning trigger — silent passes are the worst outcome.

Set USE_CPU=1 to force CPU execution (debug only; ~30x slower than GPU).
"""

import os
import sys
from typing import Optional, Tuple

import numpy as np
import torch
from cog import BasePredictor, Input, Path
from PIL import Image
from facenet_pytorch import MTCNN

from face_align import align_face_5pt

# Weights live outside /src because cog's predict runtime bind-mounts the
# host source directory over /src for live-iteration. Anything baked into
# /src at build time is masked at predict time. /opt/ is outside the mount.
WEIGHTS_DIR = "/opt/adaface_weights"
ADAFACE_INPUT_SIZE = 112


class Predictor(BasePredictor):
    def setup(self) -> None:
        force_cpu = bool(os.getenv("USE_CPU"))
        self.device = "cpu" if force_cpu or not torch.cuda.is_available() else "cuda"
        print(f"AdaFace setup: device={self.device}", flush=True)

        # Mirror the HF README's load pattern verbatim. The bundled
        # wrapper.py opens 'pretrained_model/model.yaml' with a relative
        # path, so the cwd must be WEIGHTS_DIR at AutoModel.from_pretrained
        # call time. sys.path insertion lets transformers resolve the
        # custom_code modules.
        from transformers import AutoModel
        cwd = os.getcwd()
        os.chdir(WEIGHTS_DIR)
        if WEIGHTS_DIR not in sys.path:
            sys.path.insert(0, WEIGHTS_DIR)
        try:
            self.model = AutoModel.from_pretrained(
                WEIGHTS_DIR,
                trust_remote_code=True,
            ).to(self.device).eval()
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

    def predict(
        self,
        image1: Path = Input(description="Reference face image (e.g., the original photo)"),
        image2: Path = Input(description="Probe face image to compare against image1"),
    ) -> dict:
        emb1, conf1 = self._embed(str(image1))
        emb2, conf2 = self._embed(str(image2))

        if emb1 is None or emb2 is None:
            return {
                "cosine_similarity": None,
                "image1_face_detected": emb1 is not None,
                "image2_face_detected": emb2 is not None,
                "image1_confidence": conf1,
                "image2_confidence": conf2,
            }

        n1 = float(np.linalg.norm(emb1))
        n2 = float(np.linalg.norm(emb2))
        if n1 < 1e-8 or n2 < 1e-8:
            return {
                "cosine_similarity": None,
                "image1_face_detected": True,
                "image2_face_detected": True,
                "image1_confidence": conf1,
                "image2_confidence": conf2,
                "error": "embedding norm collapsed to zero",
            }

        cosine = float(np.dot(emb1, emb2) / (n1 * n2))
        return {
            "cosine_similarity": cosine,
            "image1_face_detected": True,
            "image2_face_detected": True,
            "image1_confidence": conf1,
            "image2_confidence": conf2,
        }

    def _embed(self, image_path: str) -> Tuple[Optional[np.ndarray], float]:
        """Returns (1-D float32 embedding, detection_confidence) or (None, 0.0)."""
        img = Image.open(image_path).convert("RGB")
        np_img = np.array(img)

        boxes, probs, landmarks = self.mtcnn.detect(img, landmarks=True)
        if boxes is None or len(boxes) == 0:
            return None, 0.0
        if probs is None or probs[0] is None:
            return None, 0.0

        # select_largest=True puts the top face at index 0.
        landmark = np.asarray(landmarks[0], dtype=np.float32)
        confidence = float(probs[0])

        try:
            aligned_rgb = align_face_5pt(np_img, landmark)
        except ValueError as e:
            print(f"alignment failed: {e}", flush=True)
            return None, confidence

        # AdaFace input convention: [-1, 1] normalized RGB.
        tensor = torch.from_numpy(aligned_rgb).permute(2, 0, 1).float() / 255.0
        tensor = (tensor - 0.5) / 0.5
        tensor = tensor.unsqueeze(0).to(self.device)

        with torch.no_grad():
            output = self.model(tensor)

        # AdaFace IR-101 wrapper returns either a tensor or a (features, norms)
        # tuple — handle both. We L2 normalize ourselves regardless.
        features = output[0] if isinstance(output, (tuple, list)) else output
        return features.detach().cpu().numpy().squeeze().astype(np.float32), confidence
