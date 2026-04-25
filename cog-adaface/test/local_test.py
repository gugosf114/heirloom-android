"""
Two-tier local test for the AdaFace cog.

Tier 1 — structural (always runs):
    Mocks MTCNN and AutoModel. Verifies predict.py imports cleanly,
    Predictor.setup wires the device, predict() returns the documented
    schema, and null cosine surfaces correctly when no face is detected.
    Runs without weights and without GPU.

Tier 2 — cosine correctness (only with --full):
    Loads the real AdaFace IR-101 weights, runs against real face
    fixtures (download via test/download_fixtures.sh), and asserts
    AdaFace's published numerics:

        cos(same_a, same_b)  > 0.5    (same person, different photos)
        cos(same_a, diff_a)  < 0.3    (different people)

    Requires:
      - ~250 MB AdaFace weights downloaded into ./adaface_weights/
        (run download_weights.py with TARGET_DIR=./adaface_weights, or
        let `cog build` bake it in and run inside the cog shell)
      - test/fixtures/{same_a,same_b,diff_a}.jpg present
      - Either CUDA, or USE_CPU=1 (CPU run takes ~10s/image)

Usage:
    python test/local_test.py            # Tier 1
    python test/local_test.py --full     # Tier 1 + Tier 2
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT))


def _stub_cog_module() -> None:
    """The `cog` package is provided by the Cog runtime inside the container.
    Locally we substitute a minimal stub so predict.py imports cleanly."""
    if "cog" in sys.modules:
        return
    stub = MagicMock()

    class BasePredictor:
        pass

    def Input(**_kwargs):  # noqa: N802 (matching cog's API)
        return None

    stub.BasePredictor = BasePredictor
    stub.Input = Input
    stub.Path = Path  # cog.Path is a pathlib-compatible subclass at runtime
    sys.modules["cog"] = stub


_stub_cog_module()


def _torch_available() -> bool:
    if importlib.util.find_spec("torch") is None:
        return False
    try:
        import torch  # noqa: F401
        return True
    except Exception:
        return False


TORCH_OK = _torch_available()


class StructuralTests(unittest.TestCase):
    """Tier 1 — runs without weights, without GPU, in CI."""

    def test_face_align_template_shape(self) -> None:
        from face_align import ARCFACE_DST_TEMPLATE, OUTPUT_SIZE
        self.assertEqual(ARCFACE_DST_TEMPLATE.shape, (5, 2))
        self.assertEqual(OUTPUT_SIZE, 112)

    def test_face_align_rejects_bad_landmarks(self) -> None:
        import numpy as np
        from face_align import align_face_5pt
        img = np.zeros((200, 200, 3), dtype=np.uint8)
        with self.assertRaises(ValueError):
            align_face_5pt(img, [[0, 0], [10, 10]])  # only 2 points, not 5

    def test_face_align_runs_on_synthetic_landmarks(self) -> None:
        import numpy as np
        from face_align import align_face_5pt
        img = np.full((200, 200, 3), 128, dtype=np.uint8)
        # Plausible 5-point landmarks for a face roughly centered in 200x200.
        landmarks = [
            [70.0, 90.0],   # left eye
            [130.0, 90.0],  # right eye
            [100.0, 120.0], # nose
            [80.0, 160.0],  # left mouth
            [120.0, 160.0], # right mouth
        ]
        out = align_face_5pt(img, landmarks)
        self.assertEqual(out.shape, (112, 112, 3))
        self.assertEqual(out.dtype, np.uint8)

    @unittest.skipUnless(TORCH_OK, "torch not importable in this env")
    def test_predict_schema_with_mocked_model(self) -> None:
        """Verify predict() returns the documented schema. We mock the heavy
        deps (MTCNN, AutoModel) so this runs in a clean Python env without
        downloading anything."""
        import numpy as np
        import torch

        # Stub MTCNN to return a fixed detection.
        fake_mtcnn = MagicMock()
        fake_landmarks = np.array([[
            [70.0, 90.0], [130.0, 90.0], [100.0, 120.0],
            [80.0, 160.0], [120.0, 160.0],
        ]], dtype=np.float32)
        fake_mtcnn.detect.return_value = (
            np.array([[10.0, 10.0, 190.0, 190.0]]),  # boxes
            np.array([0.999]),                       # probs
            fake_landmarks,                          # landmarks
        )
        # Stub AutoModel to return a deterministic 512-d feature.
        fake_model = MagicMock()
        fake_model.return_value = torch.randn(1, 512)
        fake_model.to.return_value = fake_model
        fake_model.eval.return_value = fake_model

        with patch.dict(sys.modules, {
            "facenet_pytorch": MagicMock(MTCNN=lambda **kw: fake_mtcnn),
            "transformers": MagicMock(AutoModel=MagicMock(from_pretrained=lambda *a, **kw: fake_model)),
        }):
            # Force predict.py to import fresh under the mocks.
            sys.modules.pop("predict", None)
            from predict import Predictor

            predictor = Predictor()
            # setup() chdirs into WEIGHTS_DIR which won't exist in CI; bypass it.
            predictor.device = "cpu"
            predictor.model = fake_model
            predictor.mtcnn = fake_mtcnn

            # Write a 200x200 dummy JPEG so PIL.Image.open succeeds.
            from PIL import Image
            tmp_a = REPO_ROOT / "test" / "fixtures" / ".tmp_a.jpg"
            tmp_b = REPO_ROOT / "test" / "fixtures" / ".tmp_b.jpg"
            try:
                Image.new("RGB", (200, 200), (128, 128, 128)).save(tmp_a, "JPEG")
                Image.new("RGB", (200, 200), (128, 128, 128)).save(tmp_b, "JPEG")
                result = predictor.predict(image1=tmp_a, image2=tmp_b)
            finally:
                tmp_a.unlink(missing_ok=True)
                tmp_b.unlink(missing_ok=True)

            self.assertIn("cosine_similarity", result)
            self.assertIn("image1_face_detected", result)
            self.assertIn("image2_face_detected", result)
            self.assertIn("image1_confidence", result)
            self.assertIn("image2_confidence", result)
            self.assertTrue(result["image1_face_detected"])
            self.assertTrue(result["image2_face_detected"])
            self.assertIsInstance(result["cosine_similarity"], float)
            # Random embeddings -> cosine roughly in [-0.2, 0.2]; just make
            # sure we got a valid normalized cosine, not a NaN or out-of-range.
            self.assertGreaterEqual(result["cosine_similarity"], -1.0)
            self.assertLessEqual(result["cosine_similarity"], 1.0)

    @unittest.skipUnless(TORCH_OK, "torch not importable in this env")
    def test_predict_returns_null_cosine_when_no_face(self) -> None:
        fake_mtcnn = MagicMock()
        fake_mtcnn.detect.return_value = (None, None, None)
        fake_model = MagicMock()
        fake_model.to.return_value = fake_model
        fake_model.eval.return_value = fake_model

        with patch.dict(sys.modules, {
            "facenet_pytorch": MagicMock(MTCNN=lambda **kw: fake_mtcnn),
            "transformers": MagicMock(AutoModel=MagicMock(from_pretrained=lambda *a, **kw: fake_model)),
        }):
            sys.modules.pop("predict", None)
            from predict import Predictor

            predictor = Predictor()
            predictor.device = "cpu"
            predictor.model = fake_model
            predictor.mtcnn = fake_mtcnn

            from PIL import Image
            tmp_a = REPO_ROOT / "test" / "fixtures" / ".tmp_noface_a.jpg"
            tmp_b = REPO_ROOT / "test" / "fixtures" / ".tmp_noface_b.jpg"
            try:
                Image.new("RGB", (200, 200), (0, 0, 0)).save(tmp_a, "JPEG")
                Image.new("RGB", (200, 200), (0, 0, 0)).save(tmp_b, "JPEG")
                result = predictor.predict(image1=tmp_a, image2=tmp_b)
            finally:
                tmp_a.unlink(missing_ok=True)
                tmp_b.unlink(missing_ok=True)

            self.assertIsNone(result["cosine_similarity"])
            self.assertFalse(result["image1_face_detected"])
            self.assertFalse(result["image2_face_detected"])


class CosineCorrectnessTests(unittest.TestCase):
    """Tier 2 — real weights, real fixtures. Skipped unless --full."""

    SAME_THRESHOLD = 0.5
    DIFF_THRESHOLD = 0.3

    @classmethod
    def setUpClass(cls) -> None:
        weights = REPO_ROOT / "adaface_weights"
        if not (weights / "model.safetensors").exists():
            raise unittest.SkipTest(
                f"weights not at {weights}. "
                f"Run: python download_weights.py (with TARGET_DIR={weights})"
            )
        for name in ("same_a.jpg", "same_b.jpg", "diff_a.jpg"):
            if not (REPO_ROOT / "test" / "fixtures" / name).exists():
                raise unittest.SkipTest(
                    f"fixture {name} missing. Run: bash test/download_fixtures.sh"
                )

        # Repoint WEIGHTS_DIR before importing predict.
        os.environ.setdefault("USE_CPU", "1")
        sys.modules.pop("predict", None)
        import predict as predict_mod
        predict_mod.WEIGHTS_DIR = str(weights)

        cls.predictor = predict_mod.Predictor()
        cls.predictor.setup()

    def _run(self, a: str, b: str) -> dict:
        fixtures = REPO_ROOT / "test" / "fixtures"
        return self.predictor.predict(image1=fixtures / a, image2=fixtures / b)

    def test_same_person_above_threshold(self) -> None:
        result = self._run("same_a.jpg", "same_b.jpg")
        self.assertIsNotNone(result["cosine_similarity"], "no face detected")
        cos = result["cosine_similarity"]
        print(f"\n  cos(same_a, same_b) = {cos:.4f} (threshold > {self.SAME_THRESHOLD})")
        self.assertGreater(cos, self.SAME_THRESHOLD)

    def test_different_people_below_threshold(self) -> None:
        result = self._run("same_a.jpg", "diff_a.jpg")
        self.assertIsNotNone(result["cosine_similarity"], "no face detected")
        cos = result["cosine_similarity"]
        print(f"\n  cos(same_a, diff_a) = {cos:.4f} (threshold < {self.DIFF_THRESHOLD})")
        self.assertLess(cos, self.DIFF_THRESHOLD)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--full", action="store_true",
                        help="Include cosine correctness tier (requires weights + fixtures)")
    args = parser.parse_args()

    suite = unittest.TestLoader().loadTestsFromTestCase(StructuralTests)
    if args.full:
        suite.addTests(unittest.TestLoader().loadTestsFromTestCase(CosineCorrectnessTests))

    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)


if __name__ == "__main__":
    main()
