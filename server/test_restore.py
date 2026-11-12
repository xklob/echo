import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from restore import restore


class RestoreTest(unittest.TestCase):
    def test_round_trip_and_duplicate_restore(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            body = b'generated restore fixture'
            digest = hashlib.sha256(body).hexdigest()
            (root / (digest + '.blob')).write_bytes(body)
            (root / 'manifest.blob').write_text(json.dumps({'type': 'echo_manifest_v1', 'name': 'recording.wav', 'sha256': digest}))
            self.assertEqual(1, restore(root, root / 'restored'))
            self.assertEqual(body, (root / 'restored/recording.wav').read_bytes())
            self.assertEqual(0, restore(root, root / 'restored'))

    def test_rejects_path_escape(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'manifest.blob').write_text(json.dumps({'type': 'echo_manifest_v1', 'name': '../escape', 'sha256': '0' * 64}))
            with self.assertRaises(ValueError):
                restore(root, root / 'restored')
