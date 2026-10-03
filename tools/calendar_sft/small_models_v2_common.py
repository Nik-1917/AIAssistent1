"""Repair workspace: retain the failed attempt and all original protected files."""
from collections import defaultdict
from small_models_common import ROOT, DATA, NAMES, SEED, read, write, digest, now, prompt, encode_row
from small_models_common import verify_source, freeze_inputs as verify_original_inputs

WORK = ROOT / 'build/calendar_small_models_20261003_v2'

def freeze_inputs():
    return verify_original_inputs()

def selection_score(cases, records):
    """Non-applicable metrics are None, never numeric zero or an addition term."""
    if len(cases) != len(records) or not records:
        raise ValueError('Development cases and predictions do not match')
    groups = defaultdict(list)
    for case, result in zip(cases, records):
        groups[case['expected']['intent']].append(result)
    return [sum(sum(r['passed'] is True for r in items) / len(items) for items in groups.values()) / len(groups),
            sum(sum(r['params_exact'] is True for r in items) / len(items) for items in groups.values()) / len(groups),
            sum(r['reply_clock_correct'] is True for r in records)]

def completion_loss(logits, labels):
    """Mean of each example's completion loss, independent of padded length."""
    import torch.nn.functional as F
    shifted_labels = labels[:, 1:]
    valid = shifted_labels.ne(-100)
    counts = valid.sum(dim=-1)
    if (counts == 0).any():
        raise ValueError('A row has no supervised completion tokens')
    tokens = F.cross_entropy(logits[:, :-1, :].transpose(1, 2).float(), shifted_labels,
                             ignore_index=-100, reduction='none')
    return ((tokens * valid).sum(dim=-1) / counts).mean()
