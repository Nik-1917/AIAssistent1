"""Reject changed or escaping model files, and guard concurrent training starts."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from stage_source import verify_bound_file
import run
from recovery import recover


class SourceBindingTests(unittest.TestCase):
    def test_rejects_changed_weights_and_upstream_lfs_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            file = folder / 'weights.safetensors'
            file.write_bytes(b'original')
            sha = hashlib.sha256(file.read_bytes()).hexdigest()
            item = dict(bytes=8, sha256=sha, upstream_lfs_sha256=sha)
            verify_bound_file(folder, file.name, item)
            with self.assertRaises(ValueError):
                verify_bound_file(folder, file.name, dict(item, upstream_lfs_sha256='0' * 64))
            file.write_bytes(b'changed!')
            with self.assertRaises(ValueError):
                verify_bound_file(folder, file.name, item)

    def test_rejects_a_file_outside_the_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory) / 'source'
            folder.mkdir()
            (folder.parent / 'outside').write_bytes(b'original')
            with self.assertRaises(ValueError):
                verify_bound_file(folder, '../outside', dict(bytes=8, sha256='0' * 64))

    def test_existing_training_state_cannot_be_started_again(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            state = folder / 'training.json'
            state.write_bytes(b'{"status":"COMPLETE"}')
            original = state.read_bytes()
            with patch.object(run, 'RUN', folder), self.assertRaises(ValueError):
                run.execute()
            self.assertEqual(state.read_bytes(), original)

    def test_controller_lock_excludes_a_second_controller(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(recover, 'RUN', Path(directory)):
            with recover.controller_lock():
                with self.assertRaises(OSError):
                    with recover.controller_lock():
                        pass
            with recover.controller_lock():
                pass


if __name__ == '__main__':
    unittest.main()
