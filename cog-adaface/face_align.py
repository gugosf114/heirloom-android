"""
5-point ArcFace alignment.

Standard ArcFace template — same coordinates used by InsightFace and the
original AdaFace evaluation pipeline. Maps detected MTCNN landmarks
(eyes, nose, mouth corners) to a canonical 112x112 RGB face crop via a
similarity transform.

The template is the dominant convention for face recognition; mismatching
it (e.g., using a different scale or center offset) silently degrades
embedding quality without raising an error.
"""

from typing import Sequence

import cv2
import numpy as np

# ArcFace 5-point template, in (x, y) order: left eye, right eye, nose tip,
# left mouth corner, right mouth corner. Coordinates are for a 112x112 crop.
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
    """
    Args:
        image_rgb: HxWx3 uint8 RGB array.
        landmarks: Iterable of 5 (x, y) points matching ARCFACE_DST_TEMPLATE order.
                   MTCNN's `landmarks` output (boxes, probs, landmarks) is already
                   in this order: [LE, RE, nose, LM, RM].

    Returns:
        112x112x3 uint8 RGB array, similarity-aligned to the ArcFace template.
    """
    src = np.asarray(landmarks, dtype=np.float32)
    if src.shape != (5, 2):
        raise ValueError(f"landmarks must be (5, 2); got {src.shape}")

    # estimateAffinePartial2D returns a 2x3 similarity transform matrix
    # (rotation + uniform scale + translation, no shear), which is what
    # ArcFace alignment expects.
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
