"""Finish GGUF release after preserving the failed Qwen3.5 MTP export."""
from __future__ import annotations

import os
from pathlib import Path
import traceback

from rebuild_qwen35_text_gguf import safe_move
from run_small_models_v2 import NEW_PYTHON, PYTHON, REPORT, report, run, transfer
from small_models_v2_common import NAMES, WORK, digest, freeze_inputs, now, read, write


def main():
    freeze_inputs()
    history = WORK / 'release_recovery_history'
    history.mkdir(exist_ok=False)
    write(history / 'previous_workflow.json', read(WORK / 'workflow.json'))
    safe_move(WORK / 'amp_recovery_error.json', history / 'amp_recovery_error.json')
    write(WORK / 'workflow.json', {'status': 'RUNNING', 'started_at': now(), 'owner_pid': os.getpid(),
          'driver': str(Path(__file__).resolve()), 'both_training_runs': 'COMPLETE'})
    run('qwen35_2b_text_build', NEW_PYTHON, 'rebuild_qwen35_text_gguf.py')
    run('qwen35_2b_text_evaluate', PYTHON, 'evaluate_small_models_v2.py', '--model', 'qwen35_2b')
    deliveries, errors = {}, {}
    for key in NAMES:
        try:
            deliveries[key] = transfer(key)
        except Exception as exc:
            errors[key] = str(exc)
            # Preserve a transfer already verified earlier in this same workflow.
            receipt = WORK / key / 'release/phone_transfer.json'
            if receipt.exists():
                saved = read(receipt)
                artifact = read(WORK / key / 'release/gguf_manifest.json')['model']
                if (saved['status'] == 'COMPLETE' and saved['sha256'] == artifact['sha256']
                        and saved['bytes'] == artifact['bytes'] and digest(artifact['file']) == artifact['sha256']):
                    deliveries[key] = saved
    write(WORK / 'delivery_attempt.json', {'time': now(), 'errors': errors, 'verified_deliveries': deliveries})
    missing = {key: errors.get(key, 'Transfer missing') for key in NAMES if key not in deliveries}
    error = '; '.join(key + ': ' + message for key, message in missing.items()) or None
    result = report(deliveries, error)
    run('release_audit', PYTHON, 'audit_small_models_release.py')
    write(WORK / 'workflow.json', {'status': result['execution_status'], 'completed_at': now(),
          'quality_status': result['quality_status'], 'report': str(REPORT)})
    print(result['execution_status'])


if __name__ == '__main__':
    try:
        main()
    except Exception:
        write(WORK / 'release_recovery_error.json', {'status': 'FAILED', 'time': now(),
                                                   'traceback': traceback.format_exc()})
        raise
