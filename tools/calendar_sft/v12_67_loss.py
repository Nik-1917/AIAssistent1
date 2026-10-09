"""Completion-only loss for V12.67 training."""
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
