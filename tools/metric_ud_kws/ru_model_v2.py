"""Own Russian metric encoder; raw MFCC contract is unchanged. No downloaded weights.

MIT License. Copyright (c) 2026 AI Assistant contributors.
Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions: The above copyright notice and this
permission notice shall be included in all copies or substantial portions of
the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
"""
import torch
from torch import nn
from torch.nn import functional as F


class Block(nn.Module):
    def __init__(self, incoming, outgoing, stride=(1, 1)):
        super().__init__()
        self.body = nn.Sequential(
            nn.Conv2d(incoming, outgoing, 3, stride=stride, padding=1, bias=False),
            nn.BatchNorm2d(outgoing), nn.ReLU(),
            nn.Conv2d(outgoing, outgoing, 3, padding=1, bias=False), nn.BatchNorm2d(outgoing))
        self.skip = nn.Identity() if incoming == outgoing and stride == (1, 1) else nn.Sequential(
            nn.Conv2d(incoming, outgoing, 1, stride=stride, bias=False), nn.BatchNorm2d(outgoing))

    def forward(self, x):
        return F.relu(self.body(x) + self.skip(x))


class RussianMetricEncoderV2(nn.Module):
    """Residual metric CNN with explicit time/frequency bins, 64-dimensional output.

    Statistics are fitted exclusively on training MFCCs and exported as buffers.
    Per-utterance centering of C0 removes constant log-energy gain. Downsampling
    bounds CPU cost; the final 2x3 grid preserves coarse phonetic order instead
    of collapsing all time and cepstral positions into 24 pooled features.
    This is our architecture, not the published Metric-UD-KWS ResNet15.
    """
    def __init__(self):
        super().__init__()
        self.register_buffer("mean", torch.zeros(1, 1, 40))
        self.register_buffer("scale", torch.ones(1, 1, 40))
        self.stem = nn.Sequential(nn.Conv2d(1, 24, 3, stride=(2, 1), padding=1, bias=False),
                                  nn.BatchNorm2d(24), nn.ReLU())
        self.blocks = nn.Sequential(Block(24, 24), Block(24, 32, (2, 2)), Block(32, 32),
                                    Block(32, 48, (2, 2)), Block(48, 64, (2, 2)), Block(64, 64))
        self.output = nn.Linear(64 * 2 * 3, 64)

    def forward(self, features):
        x = (features - self.mean) / self.scale
        c0 = x[:, :, :1] - x[:, :, :1].mean(dim=1, keepdim=True)
        x = torch.cat((c0, x[:, :, 1:]), dim=2)
        x = self.blocks(self.stem(x.unsqueeze(1)))  # [B,64,7,5]
        x = F.avg_pool2d(x, kernel_size=(4, 3), stride=(3, 1))  # [B,64,2,3]
        return self.output(x.flatten(1))
