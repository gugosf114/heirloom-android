"""
5-point ArcFace alignment (ported verbatim from cog-adaface/face_align.py).

Maps detected MTCNN landmarks (eyes, nose, mouth corners) to a canonical
112x112 RGB face crop via a similarity transform. Same template used by
InsightFace and the original AdaFace evaluation pipeline.
"""

from typing import Sequence

import cv2
import numpy as np

# ArcFace 5-point template, (x, y): left eye, right eye, nose, left mouth, right mouth.
ARCFACE_DST_TEMPLATE = np.array(
    [
        [38.2946, 51.6963],
        [73.5318, 51.5014],
        [56.0252, 71.7366],
        [41.5493, 92.3655],
        [70.7299, 92.2041],
    ],
    dtype=np.float32,
)

OUTPUT_SIZE = 112


def align_face_5pt(image_rgb: np.ndarray, landmarks: Sequence[Sequence[float]]) -> np.ndarray:
    src = np.asarray(landmarks, dtype=np.float32)
    if src.shape != (5, 2):
        raise ValueError(f"landmarks must be (5, 2); got {src.shape}")

    M, _ = cv2.estimateAffinePartial2D(src, ARCFACE_DST_TEMPLATE, method=cv2.LMEDS)
    if M is None:
        raise ValueError("estimateAffinePartial2D failed; landmarks may be degenerate")

    aligned_bgr = cv2.warpAffine(
        cv2.cvtColor(image_rgb, cv2.COLOR_RGB2BGR),
        M,
        (OUTPUT_SIZE, OUTPUT_SIZE),
        borderValue=0.0,
    )
    return cv2.cvtColor(aligned_bgr, cv2.COLOR_BGR2RGB)
