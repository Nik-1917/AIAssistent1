"""Focused tests for strict and semantic holdout comparison."""

from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from dataset_contract import DatasetContractError, load_jsonl
from evaluate_predictions import (
    DEFAULT_HOLDOUT,
    DEFAULT_SEMANTIC_ACCEPTANCE,
    load_semantic_acceptance,
    params_semantically_equal,
)


class SemanticParamsTest(unittest.TestCase):
    def test_add_title_case_is_semantically_equivalent(self) -> None:
        expected = {"title": "Контроль витрин", "date": "2029-09-03"}
        actual = {"title": "контроль витрин", "date": "2029-09-03"}

        self.assertTrue(params_semantically_equal(actual, expected))
        self.assertNotEqual(actual, expected)

    def test_update_replacement_title_case_is_semantically_equivalent(self) -> None:
        expected = {
            "target": {"query": "лекция"},
            "changes": {"title": "Открытая лекция"},
        }
        actual = {
            "target": {"query": "лекция"},
            "changes": {"title": "открытая лекция"},
        }

        self.assertTrue(params_semantically_equal(actual, expected))

    def test_query_case_is_semantically_equivalent(self) -> None:
        expected = {"query": "осмотр резервуара"}

        self.assertTrue(params_semantically_equal({"query": "Осмотр резервуара"}, expected))

    def test_query_wording_requires_a_reviewed_alternative(self) -> None:
        expected = {"query": "визит"}
        actual = {"query": "визиты к подопечным"}

        self.assertFalse(params_semantically_equal(actual, expected))
        self.assertTrue(params_semantically_equal(actual, expected, (actual,)))

    def test_title_wording_requires_a_reviewed_alternative(self) -> None:
        expected = {"title": "Поздравить бабушку", "date": "2027-01-01"}
        actual = {
            "title": "Поздравление с днём рождения бабушке",
            "date": "2027-01-01",
        }

        self.assertFalse(params_semantically_equal(actual, expected))
        self.assertTrue(params_semantically_equal(actual, expected, (actual,)))

    def test_shortened_query_is_not_implicitly_equivalent(self) -> None:
        expected = {"query": "осмотр резервуара"}

        self.assertFalse(params_semantically_equal({"query": "осмотр"}, expected))

    def test_non_title_values_remain_strict(self) -> None:
        expected = {"title": "Осмотр", "duration_min": 40}

        self.assertFalse(params_semantically_equal({"title": "осмотр", "duration_min": 60}, expected))

    def test_reviewed_policy_contains_only_h003_and_h017(self) -> None:
        accepted = load_semantic_acceptance(
            DEFAULT_SEMANTIC_ACCEPTANCE,
            DEFAULT_HOLDOUT,
            load_jsonl(DEFAULT_HOLDOUT),
        )

        self.assertEqual({"H003", "H017"}, set(accepted))
        self.assertEqual(
            "Поздравление с днём рождения бабушке",
            accepted["H003"][0]["title"],
        )
        self.assertEqual("визиты к подопечным", accepted["H017"][0]["query"])

    def test_reviewed_policy_rejects_a_different_holdout_hash(self) -> None:
        payload = json.loads(DEFAULT_SEMANTIC_ACCEPTANCE.read_text(encoding="utf-8"))
        payload["holdout_sha256"] = "0" * 64
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "semantic_acceptance.json"
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

            with self.assertRaisesRegex(DatasetContractError, "holdout SHA-256"):
                load_semantic_acceptance(path, DEFAULT_HOLDOUT, load_jsonl(DEFAULT_HOLDOUT))


if __name__ == "__main__":
    unittest.main()
