"""Integration checks for deterministic calendar dataset staging."""

from __future__ import annotations

import unittest

from dataset_contract import load_jsonl, normalized_user_prompt
from prepare_dataset import (
    DEFAULT_HOLDOUT,
    DEFAULT_TRAIN,
    DEFAULT_VALIDATION,
    exclude_holdout_prompt_overlaps,
)


def load_sources(paths):
    return [row for path in paths for row in load_jsonl(path)]


class DatasetStagingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.raw_train = load_sources(DEFAULT_TRAIN)
        cls.raw_validation = load_sources(DEFAULT_VALIDATION)
        cls.holdout = load_sources(DEFAULT_HOLDOUT)
        cls.holdout_prompts = {normalized_user_prompt(row) for row in cls.holdout}

    def test_known_raw_split_sizes_are_stable(self) -> None:
        self.assertEqual(1231, len(self.raw_train))
        self.assertEqual(322, len(self.raw_validation))
        self.assertEqual(57, len(self.holdout))

    def test_holdout_wording_is_excluded_without_mutating_sources(self) -> None:
        train, excluded_train = exclude_holdout_prompt_overlaps(self.raw_train, self.holdout_prompts)
        validation, excluded_validation = exclude_holdout_prompt_overlaps(
            self.raw_validation,
            self.holdout_prompts,
        )

        self.assertEqual(1207, len(train))
        self.assertEqual(319, len(validation))
        self.assertEqual(24, len(excluded_train))
        self.assertEqual(3, len(excluded_validation))
        self.assertFalse({normalized_user_prompt(row) for row in train} & self.holdout_prompts)
        self.assertFalse({normalized_user_prompt(row) for row in validation} & self.holdout_prompts)


if __name__ == "__main__":
    unittest.main()
