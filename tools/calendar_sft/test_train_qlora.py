"""Unit tests for deterministic QLoRA optimizer-step planning."""

from __future__ import annotations

import unittest

from dataset_contract import DatasetContractError
from train_qlora import optimizer_steps_for_epochs


class OptimizerStepPlanningTest(unittest.TestCase):
    def test_v5_remainder_is_an_optimizer_step(self) -> None:
        self.assertEqual(59, optimizer_steps_for_epochs(943, 1, 16, 1.0))

    def test_v6_remainder_is_an_optimizer_step(self) -> None:
        self.assertEqual(77, optimizer_steps_for_epochs(1231, 1, 16, 1.0))

    def test_exactly_divisible_epoch_has_no_extra_step(self) -> None:
        self.assertEqual(2, optimizer_steps_for_epochs(32, 1, 16, 1.0))

    def test_batching_is_applied_before_accumulation(self) -> None:
        self.assertEqual(4, optimizer_steps_for_epochs(100, 4, 8, 1.0))

    def test_fractional_and_multiple_epochs_round_only_optimizer_steps(self) -> None:
        self.assertEqual(30, optimizer_steps_for_epochs(943, 1, 16, 0.5))
        self.assertEqual(118, optimizer_steps_for_epochs(943, 1, 16, 2.0))

    def test_empty_training_data_is_rejected(self) -> None:
        with self.assertRaises(DatasetContractError):
            optimizer_steps_for_epochs(0, 1, 16, 1.0)


if __name__ == "__main__":
    unittest.main()
