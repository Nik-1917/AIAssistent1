"""Meaningful invariants for manual curation and real-recording episode selection."""
import json
from pathlib import Path
import random
import unittest

from prepare_russian_v3 import balanced
from train_russian_v3 import choose_episode


class CurationContractTest(unittest.TestCase):
    def test_manual_words_pairs_and_heldout_labels_are_disjoint(self):
        plan = json.loads((Path(__file__).parent / "curation/ru_v3_words.json").read_text(encoding="utf-8"))
        words = [w for group in plan["groups"].values() for w in group]
        self.assertEqual(len(words), 384)
        self.assertEqual(len(set(words)), 384)
        self.assertFalse(set(words) & set(plan["fresh_dev_words"] + plan["fresh_test_words"]))
        for a, b in plan["contrast_pairs"]:
            self.assertNotEqual(a, b)
            self.assertTrue({a, b} <= set(words))
        self.assertEqual(plan["additional_clips"] - len(words) * plan["new_word_clips"], 32768)

    def test_small_evaluation_prefix_contains_actual_repeat_utterances(self):
        rows = [dict(path=f"word/owner{owner}_{j}.opus", speaker=str(owner)) for owner in range(10) for j in range(3)]
        selected = balanced(rows, True, pair_first=True)[:8]
        self.assertEqual(len({r["path"] for r in selected}), 8)
        self.assertEqual(len({r["speaker"] for r in selected}), 4)
        for owner in {r["speaker"] for r in selected}:
            self.assertEqual(sum(r["speaker"] == owner for r in selected), 2)

    def test_contrasts_do_not_merge_labels_or_reuse_enrollment_query_audio(self):
        indices = {i: list(range(i * 4, i * 4 + 4)) for i in range(30)}
        pairs = [(0, 1), (1, 2), (3, 4), (5, 6), (7, 8), (9, 10), (11, 12)]
        rng = random.Random(123)
        modes = set()
        for _ in range(200):
            chosen, selected, kind = choose_episode(rng, indices, {"owner": indices}, ["owner"], pairs, 24)
            modes.add(kind)
            self.assertEqual(len(set(chosen)), 24)
            self.assertEqual(len(set(selected)), 48)
            for i, word in enumerate(chosen):
                self.assertIn(selected[i], indices[word])
                self.assertIn(selected[i + 24], indices[word])
            if kind == "manual_contrasts":
                self.assertTrue(any(a in chosen and b in chosen for a, b in pairs))
        self.assertEqual(modes, {"same_owner", "ordinary_cross_speaker", "manual_contrasts"})

    def test_homophone_negatives_are_excluded_in_every_training_episode_mode(self):
        indices = {i: list(range(i * 4, i * 4 + 4)) for i in range(30)}
        exclusions = [frozenset((0, 1)), frozenset((2, 3))]
        rng = random.Random(456)
        for _ in range(200):
            chosen, _, _ = choose_episode(rng, indices, {"owner": indices}, ["owner"], [(0, 1), (2, 4)], 24, exclusions)
            for group in exclusions:
                self.assertLessEqual(len(set(chosen) & group), 1)


if __name__ == "__main__":
    unittest.main()
