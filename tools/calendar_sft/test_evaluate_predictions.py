"""Focused tests for strict and semantic holdout comparison."""

from __future__ import annotations

import unittest

from evaluate_predictions import params_semantically_equal


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

    def test_query_case_and_wording_remain_strict(self) -> None:
        expected = {"query": "осмотр резервуара"}

        self.assertFalse(params_semantically_equal({"query": "Осмотр резервуара"}, expected))
        self.assertFalse(params_semantically_equal({"query": "осмотр"}, expected))

    def test_non_title_values_remain_strict(self) -> None:
        expected = {"title": "Осмотр", "duration_min": 40}

        self.assertFalse(params_semantically_equal({"title": "осмотр", "duration_min": 60}, expected))


if __name__ == "__main__":
    unittest.main()
