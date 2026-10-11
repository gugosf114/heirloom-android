"""Exercise real orchestration and images; replace only GPU model calls."""
import importlib
import io
import os
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

import cv2
from PIL import Image

# GPU weights are not needed to test the routing and failure contract.
gate_module = types.ModuleType('pipeline.app.adaface')
gate_module.GATE = types.SimpleNamespace(ready=lambda: None, compare=lambda *args: (0.9, {}))
with patch.dict(sys.modules, {'pipeline.app.adaface': gate_module}):
    server = importlib.import_module('pipeline.app.server')


class PipelineContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.original = os.path.join(self.temp.name, 'model-output.png')
        Image.new('RGB', (80, 100), (120, 120, 120)).save(self.original)
        buf = io.BytesIO()
        Image.new('RGB', (80, 100), (120, 120, 120)).save(buf, 'JPEG')
        self.photo = buf.getvalue()

    def events(self, colorize=False, fail_all=False):
        def repair(*args, **kwargs):
            if fail_all:
                raise RuntimeError('model failed')
            return self.original
        def upscale(image, **kwargs):
            if fail_all:
                raise RuntimeError('model failed')
            return image
        def add_color(*args):
            Image.new('RGB', (80, 100), (160, 70, 40)).save(self.original)
            return self.original
        with patch.object(server.bopb, 'run', side_effect=repair), \
             patch.object(server.codeformer, 'run', side_effect=repair), \
             patch.object(server.esrgan, 'run', side_effect=upscale), \
             patch.object(server.ddcolor, 'run', side_effect=add_color) as color, \
             patch.object(server.GATE, 'compare', return_value=(0.9, {})) as compare:
            events = list(server.pipeline_events(self.photo, os.path.join(self.temp.name, 'work'), colorize=colorize))
            return events, color, compare

    def test_black_and_white_is_preserved_by_default(self):
        events, color, _ = self.events()
        color.assert_not_called()
        self.assertFalse(events[-1]['result']['was_colorized'])
        self.assertTrue(events[-1]['result']['restored_url'].startswith('data:image/jpeg;base64,'))

    def test_identity_is_checked_after_the_last_pixel_change(self):
        events, color, compare = self.events(colorize=True)
        color.assert_called_once()
        stages = [e['stage'] for e in events if e['kind'] == 'stage_done']
        self.assertLess(stages.index('ddcolor'), stages.index('adaface'))
        self.assertEqual(compare.call_args.args[1], self.original)
        self.assertTrue(events[-1]['result']['was_colorized'])

    def test_all_repairs_failing_cannot_produce_a_chargeable_result(self):
        with self.assertRaisesRegex(RuntimeError, 'all repair stages failed'):
            self.events(fail_all=True)

    def test_exif_orientation_is_applied_before_resizing(self):
        photo = Image.new('RGB', (80, 100), (10, 50, 100))
        exif = photo.getexif()
        exif[274] = 6
        buf = io.BytesIO()
        photo.save(buf, 'JPEG', exif=exif)
        destination = os.path.join(self.temp.name, 'normalized.jpg')
        server._normalize_input(buf.getvalue(), destination)
        with Image.open(destination) as result:
            self.assertEqual(result.size, (100, 80))


if __name__ == '__main__':
    unittest.main()
