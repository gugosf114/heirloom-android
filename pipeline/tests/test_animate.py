"""The animate stage: request body, operation parsing, and the endpoint contract."""
import base64
import importlib
import io
import os
import sys
import types
import unittest
from unittest.mock import patch

from PIL import Image

gate_module = types.ModuleType('pipeline.app.adaface')
gate_module.GATE = types.SimpleNamespace(ready=lambda: None, compare=lambda *args: (0.9, {}))
sys.modules.setdefault('pipeline.app.adaface', gate_module)
server = importlib.import_module('pipeline.app.server')
animate = importlib.import_module('pipeline.app.stages.animate')


class AnimateStageTest(unittest.TestCase):
    def test_body_carries_photo_prompt_and_length(self):
        b = animate.request_body(b'JPG', 4)
        self.assertEqual(b['instances'][0]['image']['bytesBase64Encoded'], base64.b64encode(b'JPG').decode())
        self.assertIn('Keep the exact faces', b['instances'][0]['prompt'])
        self.assertEqual(b['parameters']['durationSeconds'], 4)

    def test_operation_parsing(self):
        done = {'done': True, 'response': {'generateVideoResponse': {'generatedSamples': [{'video': {'uri': 'https://x/v.mp4'}}]}}}
        self.assertEqual(animate.video_from_operation(done)['uri'], 'https://x/v.mp4')
        refused = {'done': True, 'response': {'generateVideoResponse': {'raiMediaFilteredCount': 1, 'raiMediaFilteredReasons': ['celebrity likeness']}}}
        with self.assertRaises(RuntimeError) as ctx:
            animate.video_from_operation(refused)
        self.assertIn('refused', str(ctx.exception))
        with self.assertRaises(RuntimeError):
            animate.video_from_operation({'error': {'message': 'boom'}})

    def test_run_refuses_without_key(self):
        with patch.dict(os.environ, {'GEMINI_API_KEY': ''}):
            with self.assertRaises(RuntimeError):
                animate.run('/nonexistent.jpg', '/tmp')


class AnimateEndpointTest(unittest.TestCase):
    def photo(self):
        buf = io.BytesIO(); Image.new('RGB', (80, 100), (120, 120, 120)).save(buf, 'JPEG'); return buf.getvalue()

    def test_endpoint_returns_video_data_url(self):
        from fastapi.testclient import TestClient
        def fake_run(src, out_dir, seconds=4):
            os.makedirs(out_dir, exist_ok=True); p = os.path.join(out_dir, 'animation.mp4')
            open(p, 'wb').write(b'MP4' * 5000); return p
        with patch.object(server.animate, 'run', side_effect=fake_run):
            r = TestClient(server.app).post('/animate', files={'image': ('in.jpg', self.photo(), 'image/jpeg')})
        self.assertEqual(r.status_code, 200)
        self.assertTrue(r.json()['video_url'].startswith('data:video/mp4;base64,'))
        self.assertEqual(r.json()['seconds'], 4)

    def test_endpoint_reports_failure_without_video(self):
        from fastapi.testclient import TestClient
        with patch.object(server.animate, 'run', side_effect=RuntimeError('veo refused')):
            r = TestClient(server.app).post('/animate', files={'image': ('in.jpg', self.photo(), 'image/jpeg')})
        self.assertEqual(r.status_code, 500)
        self.assertNotIn('video_url', r.json())


if __name__ == '__main__':
    unittest.main()
