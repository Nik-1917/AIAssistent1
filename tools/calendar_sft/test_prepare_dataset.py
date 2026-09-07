"""Integration checks for deterministic calendar dataset staging."""

from __future__ import annotations

import unittest

from dataset_contract import load_jsonl, normalized_user_prompt
from prepare_dataset import (
    DEFAULT_HOLDOUT,
    DEFAULT_TRAIN,
    DEFAULT_VALIDATION,
    V14_ALLOWED_INTENTS,
    V14_FORBIDDEN_INTENT_NAMES,
    assistant_payload,
    exclude_holdout_prompt_overlaps,
    select_v14_rows,
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
        cls.train, cls.excluded_train_contract = select_v14_rows(cls.raw_train)
        cls.validation, cls.excluded_validation_contract = select_v14_rows(cls.raw_validation)

    def test_known_raw_split_sizes_are_stable(self) -> None:
        self.assertEqual(1496, len(self.raw_train))
        self.assertEqual(465, len(self.raw_validation))
        self.assertEqual(60, len(self.holdout))

    def test_v14_filter_removes_every_disallowed_intent(self) -> None:
        self.assertEqual(1311, len(self.train))
        self.assertEqual(399, len(self.validation))
        self.assertEqual(
            {"forbidden_intent": 185, "forbidden_literal": 0},
            self.excluded_train_contract,
        )
        self.assertEqual(
            {"forbidden_intent": 66, "forbidden_literal": 0},
            self.excluded_validation_contract,
        )
        for row in self.train + self.validation + self.holdout:
            self.assertIn(assistant_payload(row)["intent"], V14_ALLOWED_INTENTS)
            serialized = str(row)
            self.assertTrue(all(name not in serialized for name in V14_FORBIDDEN_INTENT_NAMES))

    def test_holdout_wording_is_excluded_without_mutating_sources(self) -> None:
        train, excluded_train = exclude_holdout_prompt_overlaps(self.train, self.holdout_prompts)
        validation, excluded_validation = exclude_holdout_prompt_overlaps(
            self.validation,
            self.holdout_prompts,
        )

        self.assertEqual(1311, len(train))
        self.assertEqual(399, len(validation))
        self.assertEqual(0, len(excluded_train))
        self.assertEqual(0, len(excluded_validation))
        self.assertFalse({normalized_user_prompt(row) for row in train} & self.holdout_prompts)
        self.assertFalse({normalized_user_prompt(row) for row in validation} & self.holdout_prompts)


if __name__ == "__main__":
    unittest.main()
