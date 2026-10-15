"""Album-page split: box parsing and the endpoint contract."""
import importlib, io, os, sys, types, unittest
from unittest.mock import patch
from PIL import Image

gate_module = types.ModuleType('pipeline.app.adaface')
gate_module.GATE = types.SimpleNamespace(ready=lambda: None, compare=lambda *args: (0.9, {}))
sys.modules.setdefault('pipeline.app.adaface', gate_module)
server = importlib.import_module('pipeline.app.server')
split = importlib.import_module('pipeline.app.stages.split')


class SplitTest(unittest.TestCase):
    def test_parse_cleans_and_clamps(self):
        boxes = split.parse_boxes('{"photos":[{"box_2d":[54,57,403,410],"label":"one"},{"box_2d":[-5,0,1200,999.6],"label":"big"},{"box_2d":[10,10,15,900]},{"box_2d":[1,2,3]}]}')
        self.assertEqual([b['box'] for b in boxes], [[54, 57, 403, 410], [0, 0, 1000, 1000]])
        self.assertEqual(boxes[0]['label'], 'one')

    def test_endpoint_returns_boxes(self):
        from fastapi.testclient import TestClient
        buf = io.BytesIO(); Image.new('RGB', (200, 300), (230, 220, 190)).save(buf, 'JPEG')
        with patch.object(server.split, 'run', return_value=[{'box': [10, 10, 500, 500], 'label': 'x'}]):
            r = TestClient(server.app).post('/split', files={'image': ('page.jpg', buf.getvalue(), 'image/jpeg')})
        self.assertEqual(r.status_code, 200); self.assertEqual(r.json()['photos'][0]['box'], [10, 10, 500, 500])

    def test_endpoint_fails_honestly(self):
        from fastapi.testclient import TestClient
        buf = io.BytesIO(); Image.new('RGB', (200, 300)).save(buf, 'JPEG')
        with patch.object(server.split, 'run', side_effect=RuntimeError('down')):
            r = TestClient(server.app).post('/split', files={'image': ('page.jpg', buf.getvalue(), 'image/jpeg')})
        self.assertEqual(r.status_code, 500)


if __name__ == '__main__':
    unittest.main()
