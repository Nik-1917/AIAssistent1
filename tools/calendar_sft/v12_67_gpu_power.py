"""Verify the user-approved board power cap without changing hardware settings."""
import csv
from datetime import datetime, timezone
import io
import json
import math
from pathlib import Path
import subprocess
import time


def validate_snapshot(snapshot, constraint):
    if snapshot['uuid'] != constraint['uuid'] or snapshot['index'] != 0:
        raise ValueError('Unexpected training GPU')
    if any(not math.isfinite(snapshot[key]) or snapshot[key] <= 0
           for key in ('default_watts', 'limit_watts', 'enforced_watts')):
        raise ValueError('Invalid GPU power telemetry')
    target = snapshot['default_watts'] * constraint['percent_of_default'] / 100
    if abs(target - constraint['limit_watts']) > 0.1:
        raise ValueError('Power cap differs from 60 percent of the default')
    for key in ('limit_watts', 'enforced_watts'):
        if snapshot[key] > constraint['limit_watts'] + 0.1:
            raise RuntimeError('GPU power limit exceeds the approved cap: ' + key)
    return snapshot


def read_gpu_power(constraint):
    fields = 'index,uuid,name,power.default_limit,power.limit,enforced.power.limit,power.draw,temperature.gpu,utilization.gpu'
    result = subprocess.run(['nvidia-smi', '-i', constraint['uuid'], '--query-gpu=' + fields,
                             '--format=csv,noheader,nounits'], check=True, capture_output=True,
                            text=True, encoding='utf-8', timeout=20,
                            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    rows = list(csv.reader(io.StringIO(result.stdout)))
    if len(rows) != 1 or len(rows[0]) != 9:
        raise ValueError('Unexpected GPU power query response')
    values = [value.strip() for value in rows[0]]
    snapshot = dict(index=int(values[0]), uuid=values[1], name=values[2],
                    default_watts=float(values[3]), limit_watts=float(values[4]),
                    enforced_watts=float(values[5]), draw_watts=float(values[6]),
                    temperature_c=int(values[7]), utilization_percent=int(values[8]))
    return validate_snapshot(snapshot, constraint)


class PowerGuard:
    def __init__(self, constraint, log_path):
        self.constraint = constraint
        self.log_path = Path(log_path)
        self.last_check = None
        self.last_snapshot = None

    def check(self, force=False):
        clock = time.monotonic()
        if force or self.last_check is None or clock - self.last_check >= self.constraint['check_interval_seconds']:
            snapshot = read_gpu_power(self.constraint)
            snapshot['checked_at'] = datetime.now(timezone.utc).isoformat()
            self.log_path.parent.mkdir(parents=True, exist_ok=True)
            with self.log_path.open('a', encoding='utf-8') as stream:
                stream.write(json.dumps(snapshot, ensure_ascii=False) + '\n')
            self.last_check, self.last_snapshot = clock, snapshot
        return self.last_snapshot
