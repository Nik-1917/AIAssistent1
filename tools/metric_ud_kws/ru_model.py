"""Experimental Russian encoder, trained from random initialization, never downloaded weights.

ResNet15 topology adapted from kaistmm/Metric-UD-KWS at
b26b3dffa3ab19255963329eab9e733e441d7486, models/ResNet15.py.
Unused imports and the unreachable optional pooling branch are removed.

MIT License
Copyright (c) 2018 Castorini
Copyright (c) 2023 Multimodal AI Lab, KAIST

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""
import torch
from torch import nn


class RussianResNet15(nn.Module):
    def __init__(self, n_maps=24, embedding_size=64):
        super().__init__()
        self.conv0 = nn.Conv2d(1, n_maps, 3, padding=1, bias=False)
        for i in range(13):
            dilation = 2 ** (i // 3)
            self.add_module(f"bn{i + 1}", nn.BatchNorm2d(n_maps, affine=False))
            self.add_module(f"conv{i + 1}", nn.Conv2d(n_maps, n_maps, 3,
                            padding=dilation, dilation=dilation, bias=False))
        self.output = nn.Linear(n_maps, embedding_size)

    def forward(self, features):
        x = features.unsqueeze(1)
        old_x = x
        for i in range(14):
            y = torch.relu(getattr(self, f"conv{i}")(x))
            if i == 0:
                old_x = y
            if i > 0 and i % 2 == 0:
                x = y + old_x
                old_x = x
            else:
                x = y
            if i > 0:
                x = getattr(self, f"bn{i}")(x)
        return self.output(x.flatten(2).mean(2))


def mfcc_transform():
    # Same versioned frontend as the unchanged Kotlin extractor.
    import torchaudio
    return torchaudio.transforms.MFCC(sample_rate=16000, n_mfcc=40,
        dct_type=2, norm="ortho", log_mels=False,
        melkwargs=dict(n_fft=480, win_length=480, hop_length=160, f_min=0.,
            f_max=8000., pad=0, n_mels=40, power=2., normalized=False,
            center=True, pad_mode="reflect", norm=None, mel_scale="htk",
            window_fn=torch.hann_window))
