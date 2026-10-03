"""Run the frozen trainer with bounded CUDA caching and recorded telemetry."""
from __future__ import annotations

import os
from pathlib import Path
import runpy
import sys
import threading
import time

os.environ['PYTORCH_CUDA_ALLOC_CONF'] = 'backend:native,garbage_collection_threshold:0.8'

from small_models_v2_common import ROOT, WORK, digest, now, write


def main():
    import torch
    key = sys.argv[sys.argv.index('--model') + 1]
    mode = sys.argv[sys.argv.index('--mode') + 1]
    if key not in ('qwen3_1_7b', 'qwen35_2b') or mode != 'train':
        raise ValueError('This wrapper is limited to the approved compact training runs')
    trainer = ROOT / 'tools/calendar_sft/train_small_model_v2.py'
    folder = WORK / key
    record = folder / 'cuda_memory_guard.json'
    if record.exists():
        raise ValueError('A recorded guarded attempt already exists; inspect before retrying')
    # PyTorch 2.7.1 only invokes its proactive cache collector when a process
    # memory fraction has explicitly been set (CUDACachingAllocator.cpp).
    torch.cuda.set_per_process_memory_fraction(0.8, 0)
    write(record, {'started_at': now(), 'trainer': str(trainer), 'trainer_sha256': digest(trainer),
        'wrapper_sha256': digest(__file__), 'arguments': sys.argv[1:],
        'allocator': os.environ['PYTORCH_CUDA_ALLOC_CONF'], 'process_memory_fraction': 0.8,
        'total_device_bytes': torch.cuda.get_device_properties(0).total_memory,
        'torch': torch.__version__, 'training_code_modified': False})
    stopped = threading.Event()

    def sample():
        import json
        with (folder / 'cuda_memory_samples.jsonl').open('x', encoding='utf-8') as output:
            while not stopped.is_set():
                output.write(json.dumps({'time': now(), 'allocated': torch.cuda.memory_allocated(0),
                    'reserved': torch.cuda.memory_reserved(0),
                    'max_allocated': torch.cuda.max_memory_allocated(0),
                    'max_reserved': torch.cuda.max_memory_reserved(0)}) + '\n')
                output.flush()
                stopped.wait(20)

    worker = threading.Thread(target=sample, name='cuda-memory-telemetry', daemon=True)
    worker.start()
    try:
        sys.argv[0] = str(trainer)
        runpy.run_path(str(trainer), run_name='__main__')
    finally:
        stopped.set()
        worker.join(timeout=5)


if __name__ == '__main__':
    main()
