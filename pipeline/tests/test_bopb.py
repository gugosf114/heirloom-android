import os
import tempfile
import unittest
from unittest.mock import patch

from pipeline.app.stages import bopb


class BopbStageTest(unittest.TestCase):
    @staticmethod
    def _write_result(output_dir: str) -> str:
        result = os.path.join(
            output_dir,
            "stage_1_restore_output",
            "restored_image",
            "orig.png",
        )
        os.makedirs(os.path.dirname(result), exist_ok=True)
        with open(result, "wb") as image:
            image.write(b"restored")
        return result

    def test_returns_scratch_result_without_retry(self):
        with tempfile.TemporaryDirectory() as workdir:
            output_dir = os.path.join(workdir, "output")

            def fake_run(command, **_kwargs):
                self._write_result(command[command.index("--output_folder") + 1])
                return "ok"

            with patch.object(bopb, "run_cmd", side_effect=fake_run) as run_cmd:
                result = bopb.run(os.path.join(workdir, "input"), output_dir)

            self.assertTrue(result.endswith("orig.png"))
            self.assertEqual(run_cmd.call_count, 1)
            self.assertIn("--with_scratch", run_cmd.call_args.args[0])

    def test_retries_without_scratch_when_first_pass_is_empty(self):
        with tempfile.TemporaryDirectory() as workdir:
            output_dir = os.path.join(workdir, "output")
            calls = []

            def fake_run(command, **_kwargs):
                calls.append(command)
                if "--with_scratch" not in command:
                    self._write_result(command[command.index("--output_folder") + 1])
                return "ok"

            with patch.object(bopb, "run_cmd", side_effect=fake_run):
                result = bopb.run(os.path.join(workdir, "input"), output_dir)

            self.assertTrue(result.endswith("orig.png"))
            self.assertEqual(len(calls), 2)
            self.assertIn("--with_scratch", calls[0])
            self.assertNotIn("--with_scratch", calls[1])


if __name__ == "__main__":
    unittest.main()
