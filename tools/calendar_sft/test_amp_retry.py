"""CUDA integration checks for overflow retry and dropout/RNG preservation."""
import copy
import unittest
import torch

from amp_retry import optimizer_step_with_retries


class CountingSGD(torch.optim.SGD):
    def __init__(self, params):
        super().__init__(params, lr=0.01)
        self.updates = 0

    def step(self, closure=None):
        self.updates += 1
        return super().step(closure)


@unittest.skipUnless(torch.cuda.is_available(), 'CUDA is required for the FP16 overflow check')
class AmpRetryTests(unittest.TestCase):
    def model(self):
        layer = torch.nn.Linear(4, 4, bias=False, device='cuda')
        with torch.no_grad():
            layer.weight.fill_(0.5)
        return torch.nn.Sequential(torch.nn.Dropout(0.25), layer)

    def closure(self, model, scaler, draws):
        def backward():
            with torch.autocast('cuda', dtype=torch.float16):
                output = model(torch.ones(4, 4, device='cuda'))
                loss = output.float().square().mean()
            draws.append(output.detach().clone())
            scaler.scale(loss).backward()
            return loss.item()
        return backward

    def test_overflow_repeats_same_dropout_and_updates_exactly_once(self):
        torch.manual_seed(1234)
        model = self.model()
        initial = copy.deepcopy(model.state_dict())
        cpu_rng, cuda_rng = torch.get_rng_state(), torch.cuda.get_rng_state_all()
        optimizer = CountingSGD(model.parameters())
        scaler = torch.amp.GradScaler('cuda', init_scale=2 ** 22)
        draws, overflows = [], []
        result = optimizer_step_with_retries(model.parameters(), optimizer, scaler,
            self.closure(model, scaler, draws), on_retry=overflows.append)
        final_cpu_rng, final_cuda_rng = torch.get_rng_state(), torch.cuda.get_rng_state_all()
        self.assertGreater(result['overflow_retries'], 0)
        self.assertEqual(optimizer.updates, 1)
        self.assertEqual(len(overflows), result['overflow_retries'])
        for draw in draws[1:]:
            torch.testing.assert_close(draw, draws[0], rtol=0, atol=0)
        baseline = self.model()
        baseline.load_state_dict(initial)
        torch.set_rng_state(cpu_rng)
        torch.cuda.set_rng_state_all(cuda_rng)
        baseline_optimizer = CountingSGD(baseline.parameters())
        baseline_scaler = torch.amp.GradScaler('cuda', init_scale=1.0)
        optimizer_step_with_retries(baseline.parameters(), baseline_optimizer, baseline_scaler,
            self.closure(baseline, baseline_scaler, []))
        self.assertEqual(baseline_optimizer.updates, 1)
        for actual, expected in zip(model.parameters(), baseline.parameters()):
            torch.testing.assert_close(actual, expected, rtol=0, atol=1e-6)
        self.assertTrue(torch.equal(torch.get_rng_state(), final_cpu_rng))
        self.assertTrue(all(torch.equal(a, b) for a, b in zip(torch.cuda.get_rng_state_all(), final_cuda_rng)))

    def test_retry_limit_never_updates_weights(self):
        model = self.model()
        initial = copy.deepcopy(model.state_dict())
        optimizer = CountingSGD(model.parameters())
        scaler = torch.amp.GradScaler('cuda', init_scale=2 ** 22)
        with self.assertRaisesRegex(ValueError, 'bounded scale reduction'):
            optimizer_step_with_retries(model.parameters(), optimizer, scaler,
                self.closure(model, scaler, []), max_retries=0)
        self.assertEqual(optimizer.updates, 0)
        for key, actual in model.state_dict().items():
            torch.testing.assert_close(actual, initial[key], rtol=0, atol=0)


if __name__ == '__main__':
    unittest.main()
