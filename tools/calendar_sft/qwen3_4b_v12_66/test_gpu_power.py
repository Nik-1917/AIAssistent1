"""Verify refusal to train when the requested board power cap is absent."""
from copy import deepcopy
import unittest

from gpu_power import validate_snapshot


class PowerLimitTests(unittest.TestCase):
    def setUp(self):
        self.constraint = dict(uuid='selected-gpu', percent_of_default=60, limit_watts=150)
        self.snapshot = dict(uuid='selected-gpu', index=0, default_watts=250,
                             limit_watts=150, enforced_watts=150)

    def test_accepts_verified_sixty_percent_limit(self):
        self.assertEqual(validate_snapshot(self.snapshot, self.constraint), self.snapshot)

    def test_rejects_default_power_after_driver_reset(self):
        for key in ('limit_watts', 'enforced_watts'):
            with self.subTest(key=key):
                snapshot = deepcopy(self.snapshot)
                snapshot[key] = 250
                with self.assertRaises(RuntimeError):
                    validate_snapshot(snapshot, self.constraint)

    def test_rejects_other_gpu(self):
        snapshot = dict(self.snapshot, uuid='other-gpu')
        with self.assertRaises(ValueError):
            validate_snapshot(snapshot, self.constraint)

    def test_rejects_incorrect_default_to_percentage_conversion(self):
        snapshot = dict(self.snapshot, default_watts=300)
        with self.assertRaises(ValueError):
            validate_snapshot(snapshot, self.constraint)

    def test_rejects_missing_or_nonfinite_power_telemetry(self):
        for value in (0, -1, float('nan'), float('inf')):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_snapshot(dict(self.snapshot, enforced_watts=value), self.constraint)
