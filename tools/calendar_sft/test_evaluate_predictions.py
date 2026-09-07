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
    critical_temporal_fields_match,
    extract_raw_intent,
    extract_raw_params,
    is_json_object,
    load_semantic_acceptance,
    params_semantically_equal,
    v14_output_violations,
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

    def test_v14_rejects_a_legacy_mutation_intent(self) -> None:
        output = '{"intent":"calendar_delete","reply":"Событие удалено: Проверка.","params":{"target":{"query":"проверка"}}}'
        parsed = json.loads(output)

        violations = v14_output_violations(output, parsed)

        self.assertEqual(2, len(violations))
        self.assertTrue(any("forbidden by the V14 contract" in item for item in violations))
        self.assertTrue(any("forbidden intent name" in item for item in violations))

    def test_v14_reads_unsupported_intent_before_strict_schema_validation(self) -> None:
        output = '{"intent":"calendar_remove","reply":"Удалено.","params":{}}'

        self.assertEqual("calendar_remove", extract_raw_intent(output))
        self.assertEqual(
            ("intent 'calendar_remove' is forbidden by the V14 contract",),
            v14_output_violations(output),
        )

    def test_json_object_validation_rejects_non_objects_and_malformed_json(self) -> None:
        self.assertTrue(is_json_object('{"intent":"chat"}'))
        self.assertFalse(is_json_object('"chat"'))
        self.assertFalse(is_json_object('{"intent":'))

    def test_v14_detects_forbidden_literal_in_an_invalid_response(self) -> None:
        output = '{"intent":"calendar_update","params":'

        self.assertIsNone(extract_raw_intent(output))
        self.assertEqual(
            ("output contains forbidden intent name 'calendar_update'",),
            v14_output_violations(output),
        )

    def test_v14_scores_critical_fields_independently_of_reply_validation(self) -> None:
        output = '{"intent":"calendar_add","reply":"invalid","params":{"starts_at":"2032-02-29T15:00"}}'
        actual_params = extract_raw_params(output)

        self.assertTrue(
            critical_temporal_fields_match(
                "V14H001",
                actual_params,
                {"starts_at": "2032-02-29T15:00"},
            ),
        )
        self.assertFalse(
            critical_temporal_fields_match(
                "V14H001",
                actual_params,
                {"starts_at": "2032-03-01T15:00"},
            ),
        )
        self.assertIsNone(critical_temporal_fields_match("V14H060", actual_params, {}))

    def test_v14_policy_starts_without_unreviewed_alternatives(self) -> None:
        accepted = load_semantic_acceptance(
            DEFAULT_SEMANTIC_ACCEPTANCE,
            DEFAULT_HOLDOUT,
            load_jsonl(DEFAULT_HOLDOUT),
        )

        self.assertEqual({}, accepted)

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
