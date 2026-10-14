"""The generative engine: prompt, request bodies, reply parsing, and routing."""
import base64
import importlib
import io
import os
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

from PIL import Image

# Stub the GPU gate for good (no patch.dict): a restoring context would evict cv2 and
# numpy from sys.modules on exit, and the next test module's re-import of cv2 breaks.
gate_module = types.ModuleType('pipeline.app.adaface')
gate_module.GATE = types.SimpleNamespace(ready=lambda: None, compare=lambda *args: (0.9, {}))
sys.modules.setdefault('pipeline.app.adaface', gate_module)
server = importlib.import_module('pipeline.app.server')
generative = importlib.import_module('pipeline.app.stages.generative')


class PromptTest(unittest.TestCase):
    def test_prompt_protects_identity_and_tones(self):
        p = generative.restoration_prompt(colorize=False)
        self.assertIn('same identity', p)
        self.assertIn('Do not add color', p)
        self.assertIn('do not add, remove, or move anything', p)

    def test_modes_change_the_work_but_never_the_identity_rules(self):
        full = generative.restoration_prompt(False, 'full'); repair = generative.restoration_prompt(False, 'repair')
        sharpen = generative.restoration_prompt(False, 'sharpen'); color = generative.restoration_prompt(False, 'color')
        for p in (full, repair, sharpen, color): self.assertIn('same identity', p)
        self.assertIn('Do not sharpen', repair); self.assertIn('Do not repair', sharpen)
        self.assertIn('change nothing else', color); self.assertIn('Add realistic', color)
        self.assertEqual(generative.restoration_prompt(False, 'bogus'), full)

    def test_color_only_when_asked(self):
        self.assertIn('Add realistic', generative.restoration_prompt(colorize=True))
        self.assertNotIn('Add realistic', generative.restoration_prompt(colorize=False))


class RequestAndReplyTest(unittest.TestCase):
    def test_openai_multipart_carries_fields_and_image(self):
        body, ctype = generative.openai_multipart(b'JPEGBYTES', 'fix it', 'gpt-image-1.5', 'high', 'high')
        self.assertTrue(ctype.startswith('multipart/form-data; boundary='))
        for needle in (b'name="model"\r\n\r\ngpt-image-1.5', b'name="input_fidelity"\r\n\r\nhigh',
                       b'name="output_format"\r\n\r\njpeg', b'filename="input.jpg"', b'JPEGBYTES'):
            self.assertIn(needle, body)

    def test_openai_fidelity_can_be_dropped(self):
        body, _ = generative.openai_multipart(b'x', 'p', 'm', '', 'medium')
        self.assertNotIn(b'input_fidelity', body)

    def test_openai_reply_parsing(self):
        self.assertEqual(generative.extract_openai_image({'data': [{'b64_json': base64.b64encode(b'img').decode()}]}), b'img')
        with self.assertRaises(RuntimeError):
            generative.extract_openai_image({'error': {'message': 'nope'}})
        with self.assertRaises(RuntimeError):
            generative.extract_openai_image({'data': []})

    def test_gemini_body_and_reply(self):
        body = generative.gemini_body(b'img', 'prompt')
        self.assertEqual(body['contents'][0]['parts'][0]['text'], 'prompt')
        self.assertEqual(body['contents'][0]['parts'][1]['inline_data']['data'], base64.b64encode(b'img').decode())
        reply = {'candidates': [{'content': {'parts': [{'text': 'here'}, {'inlineData': {'data': base64.b64encode(b'out').decode()}}]}}]}
        self.assertEqual(generative.extract_gemini_image(reply), b'out')
        with self.assertRaises(RuntimeError):
            generative.extract_gemini_image({'candidates': [{'content': {'parts': [{'text': 'only text'}]}}]})

    def test_run_refuses_without_a_key(self):
        with patch.dict(os.environ, {'RESTORE_PROVIDER': 'openai', 'OPENAI_API_KEY': ''}):
            with tempfile.TemporaryDirectory() as d:
                src = os.path.join(d, 'in.jpg'); Image.new('RGB', (8, 8)).save(src)
                with self.assertRaises(RuntimeError):
                    generative.run(src, d, colorize=False)


class GenerativeRoutingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.restored = os.path.join(self.temp.name, 'restored.jpg')
        Image.new('RGB', (80, 100), (120, 120, 120)).save(self.restored)
        buf = io.BytesIO(); Image.new('RGB', (80, 100), (120, 120, 120)).save(buf, 'JPEG'); self.gray_photo = buf.getvalue()
        buf = io.BytesIO(); Image.new('RGB', (80, 100), (200, 60, 30)).save(buf, 'JPEG'); self.color_photo = buf.getvalue()

    def events(self, photo, colorize=False, fail=False):
        def gen(in_path, out_dir, colorize, **kwargs):
            if fail: raise RuntimeError('provider down')
            return self.restored
        with patch.object(server, 'RESTORE_ENGINE', 'generative'), \
             patch.object(server.generative, 'run', side_effect=gen) as g, \
             patch.object(server.bopb, 'run') as b, patch.object(server.codeformer, 'run') as c, \
             patch.object(server.ddcolor, 'run') as d, \
             patch.object(server.esrgan, 'run', side_effect=lambda img, **k: img), \
             patch.object(server.GATE, 'compare', return_value=(0.9, {})):
            events = list(server.pipeline_events(photo, os.path.join(self.temp.name, 'w'), colorize=colorize))
            return events, g, b, c, d

    def test_one_call_replaces_the_classic_chain(self):
        events, g, b, c, d = self.events(self.gray_photo)
        g.assert_called_once(); b.assert_not_called(); c.assert_not_called(); d.assert_not_called()
        stages = [e['stage'] for e in events if e['kind'] == 'stage_done']
        self.assertEqual(stages[0], 'generative'); self.assertIn('esrgan', stages); self.assertIn('adaface', stages)
        self.assertFalse(events[-1]['result']['was_colorized'])

    def test_color_flag_flows_into_the_call_only_for_gray_input(self):
        events, g, *_ = self.events(self.gray_photo, colorize=True)
        self.assertTrue(g.call_args.kwargs['colorize']); self.assertTrue(events[-1]['result']['was_colorized'])
        events, g, *_ = self.events(self.color_photo, colorize=True)
        self.assertFalse(g.call_args.kwargs['colorize']); self.assertFalse(events[-1]['result']['was_colorized'])

    def test_mode_reaches_the_model_call_and_color_mode_colorizes(self):
        def gen(in_path, out_dir, colorize, mode='full'):
            return self.restored
        with patch.object(server, 'RESTORE_ENGINE', 'generative'), \
             patch.object(server.generative, 'run', side_effect=gen) as g, \
             patch.object(server.esrgan, 'run', side_effect=lambda img, **k: img), \
             patch.object(server.GATE, 'compare', return_value=(0.9, {})):
            events = list(server.pipeline_events(self.gray_photo, os.path.join(self.temp.name, 'w2'), colorize=False, mode='color'))
        self.assertEqual(g.call_args.kwargs['mode'], 'color'); self.assertTrue(g.call_args.kwargs['colorize'])
        self.assertTrue(events[-1]['result']['was_colorized'])

    def test_provider_failure_still_upscales_the_original_or_fails_honestly(self):
        # Generative down + ESRGAN up: the photo is only upscaled, never charged as "restored" by the model.
        events, *_ = self.events(self.gray_photo, fail=True)
        done = {e['stage']: e for e in events if e['kind'] == 'stage_done'}
        self.assertTrue(done['generative']['extra']['skipped'])
        self.assertEqual(events[-1]['kind'], 'final')


if __name__ == '__main__':
    unittest.main()
