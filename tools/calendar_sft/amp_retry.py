"""Retry an overflowing AMP batch without advancing data, weights or scheduler."""
from __future__ import annotations

import math
import random
import torch


def optimizer_step_with_retries(parameters, optimizer, scaler, backward, *, on_retry=None,
                                max_retries=12, minimum_scale=2 ** -16):
    """The callback backpropagates one complete accumulated batch and returns loss.

    Forward must not mutate persistent model buffers. The calendar models use
    RMSNorm and use_cache=False. RNG restoration reproduces the same dropout
    draws for every retry; scheduler/data advancement belongs to the caller.
    """
    if not scaler.is_enabled():
        raise ValueError('This update requires an enabled CUDA GradScaler')
    parameters = tuple(parameters)
    python_rng = random.getstate()
    cpu_rng = torch.get_rng_state()
    cuda_rng = torch.cuda.get_rng_state_all()
    for attempt in range(max_retries + 1):
        if attempt:
            random.setstate(python_rng)
            torch.set_rng_state(cpu_rng)
            torch.cuda.set_rng_state_all(cuda_rng)
        optimizer.zero_grad(set_to_none=True)
        loss = backward()
        if not math.isfinite(loss):
            raise ValueError('Non-finite forward loss cannot be repaired by gradient scaling')
        scaler.unscale_(optimizer)
        norm = torch.nn.utils.clip_grad_norm_(parameters, 1.0)
        if torch.isfinite(norm):
            scaler.step(optimizer)
            scaler.update()
            return {'loss': loss, 'gradient_norm': norm.item(), 'overflow_retries': attempt,
                    'scale': scaler.get_scale()}
        # Only GradScaler-detected non-finite gradients permit its guaranteed
        # skipped step. A finite-gradient norm overflow is a separate failure.
        if all(parameter.grad is None or torch.isfinite(parameter.grad).all() for parameter in parameters):
            raise ValueError('Non-finite gradient norm with individually finite gradients')
        previous_scale = scaler.get_scale()
        scaler.step(optimizer)  # Recorded inf/nan causes GradScaler to skip optimizer.step.
        scaler.update()
        current_scale = scaler.get_scale()
        if on_retry is not None:
            on_retry({'attempt': attempt + 1, 'previous_scale': previous_scale, 'next_scale': current_scale})
        optimizer.zero_grad(set_to_none=True)
        if current_scale >= previous_scale or current_scale < minimum_scale or attempt == max_retries:
            raise ValueError('AMP gradients remain non-finite after bounded scale reduction')
    raise AssertionError('Unreachable AMP retry state')
