"""Launch the approved pipeline in a hidden process with exclusive log files."""
import os
import subprocess
import sys

from run_context import ROOT, HERE, RUN, read, write, digest, now, verify_preservation, CONFIG
from gpu_power import read_gpu_power


def main():
    if (RUN / 'pipeline.json').exists() or (RUN / 'training.json').exists() or (RUN / 'controller_launch.json').exists():
        raise ValueError('An existing launch must be inspected or recovered explicitly')
    validation = read(RUN / 'validation.json')
    if validation['status'] != 'PASS':
        raise ValueError('Successful isolated validation is required')
    verify_preservation()
    read_gpu_power(CONFIG['gpu_power'])
    command = [sys.executable, '-X', 'utf8', '-B', str(HERE / 'run.py')]
    with (RUN / 'controller.stdout.log').open('xb') as out, (RUN / 'controller.stderr.log').open('xb') as err:
        child = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                 creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    record = dict(status='LAUNCHED', launched_at=now(), launcher_pid=child.pid, command=command,
                  controller_sha256=digest(HERE / 'run.py'), physical_device_inference='NOT_RUN')
    write(RUN / 'controller_launch.json', record)
    print(record, flush=True)


if __name__ == '__main__':
    main()
