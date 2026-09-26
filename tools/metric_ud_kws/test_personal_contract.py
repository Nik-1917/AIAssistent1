"""Tests for evaluation leakage and batched MFCC semantics, not accuracy simulation."""
import unittest

import numpy as np
import torch

from evaluate_personal import calibrate, report, scores, thresholds_from, trials
from ru_model import mfcc_transform
from ru_model_v2 import RussianMetricEncoderV2


class PersonalEvaluationContractTest(unittest.TestCase):
    def test_enrollment_never_reused_as_positive_and_negatives_are_same_owner(self):
        rows = [dict(word=word, speaker=owner) for owner in ("a", "b")
                for word in ("first", "second") for _ in range(3)]
        plan = trials(rows)
        self.assertEqual(len(plan["supports"]), 4)
        self.assertEqual(len(plan["positive"]), 8)
        for s, q in plan["positive"]:
            self.assertNotEqual(s, q)
            self.assertEqual(rows[s], rows[q])
        for s, q in plan["negative"]:
            self.assertEqual(rows[s]["speaker"], rows[q]["speaker"])
            self.assertNotEqual(rows[s]["word"], rows[q]["word"])

    def test_ties_do_not_exceed_dev_false_accept_budget(self):
        negative = np.repeat(np.linspace(-.5, .9, 50, dtype=np.float32), 10)
        for target in (.01, .025, .05, .1):
            self.assertLessEqual(float((negative >= calibrate(negative, target)).mean()), target)

    def test_final_test_uses_supplied_threshold_even_if_all_test_negatives_fail(self):
        dev = report(dict(positive=np.array([.9, .95]), negative=np.array([.1, .2]), cross_speaker=np.array([.8])))
        test = report(dict(positive=np.array([.1]), negative=np.array([.99]), cross_speaker=np.array([.2])), thresholds_from(dev))
        self.assertEqual(test["threshold"], dev["threshold"])
        self.assertEqual(test["operating_points"]["0.01"]["false_accept_rate"], 1.)

    def test_batch_mfcc_matches_independent_clips_with_different_energy(self):
        torch.set_num_threads(1)
        torch.manual_seed(123)
        wave = torch.randn(3, 16000) * torch.tensor([.1, .001, .00001])[:, None]
        mfcc = mfcc_transform()
        batch = mfcc(wave.unsqueeze(1)).squeeze(1)
        independent = torch.stack([mfcc(w) for w in wave])
        torch.testing.assert_close(batch, independent, atol=1e-4, rtol=1e-5)

    def test_v2_encoder_energy_centering_is_in_exported_model(self):
        torch.set_num_threads(1)
        model = RussianMetricEncoderV2().eval()
        torch.manual_seed(124)
        feature = torch.randn(1, 101, 40)
        shifted = feature.clone()
        shifted[:, :, 0] += 20.
        with torch.no_grad():
            value, other = model(feature), model(shifted)
        self.assertEqual(tuple(value.shape), (1, 64))
        self.assertTrue(torch.isfinite(value).all())
        torch.testing.assert_close(value, other, atol=1e-5, rtol=1e-4)


if __name__ == "__main__":
    unittest.main()
