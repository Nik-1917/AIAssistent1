"""Data-leakage/admission tests. Uses tiny invented numeric features, no audio/network."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import numpy as np

from prepare_russian import digest, speaker_split
from train_russian import load_corpus


class RussianCorpusContractTest(unittest.TestCase):
    def corpus(self, root):
        rows = []
        files = {}
        for i, split in enumerate(("train", "dev", "test")):
            path = root / f"{split}_features.npy"
            np.save(path, np.full((1, 101, 40), i, dtype=np.float32))
            files[path.name] = digest(path)
            rows.append(dict(split=split, label=0, word=f"word{i}", speaker=f"speaker{i}",
                path=f"word{i}/source{i}.opus", pcm_sha256=hashlib.sha256(str(i).encode()).hexdigest()))
        manifest = dict(license="CC-BY-4.0", feature_version="metric-cli-mfcc40-1s-v1", files=files, samples=rows)
        self.write(root, manifest)
        return manifest

    def write(self, root, manifest):
        (root / "corpus.json").write_text(json.dumps(manifest), encoding="utf-8")

    def test_unchanged_disjoint_corpus_is_loadable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.corpus(root)
            _, values, _ = load_corpus(root)
            self.assertEqual(values["test"].shape, (1, 101, 40))

    def test_leakage_is_rejected_independently_of_feature_hashes(self):
        for key in ("word", "speaker", "path", "pcm_sha256"):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                manifest = self.corpus(root)
                manifest["samples"][2][key] = manifest["samples"][0][key]
                self.write(root, manifest)
                with self.assertRaisesRegex(ValueError, "Leakage"):
                    load_corpus(root)

    def test_same_source_sentence_different_word_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = self.corpus(root)
            manifest["samples"][2]["path"] = "different-word/source0.opus"
            self.write(root, manifest)
            with self.assertRaisesRegex(ValueError, "Source sentence leakage"):
                load_corpus(root)

    def test_changed_feature_bytes_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.corpus(root)
            path = root / "test_features.npy"
            content = bytearray(path.read_bytes())
            content[-1] ^= 1
            path.write_bytes(content)
            with self.assertRaisesRegex(ValueError, "Changed features"):
                load_corpus(root)

    def test_invalid_speaker_metadata_is_not_bucketed(self):
        for value in ("", "unknown", "../path", "a" * 127, "g" * 128):
            with self.assertRaises(ValueError):
                speaker_split(value)


if __name__ == "__main__":
    unittest.main()
