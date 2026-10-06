"""Bind the existing official 4B snapshot to a fresh isolated V12.66 run."""
from run_context import ROOT, WORK, SOURCE_LOCK, CONFIG, read, write, digest, now, snapshot_protected
from verify_source_snapshot import verify_staged_source
from gpu_power import read_gpu_power


def main():
    upstream_lock_path = ROOT / 'tools/calendar_sft/clean_room_qwen3_source_lock.json'
    upstream_lock = read(upstream_lock_path)
    source = ROOT / 'build/calendar_sft_models/Qwen3-4B-Instruct-2507' / upstream_lock['source']['revision']
    if SOURCE_LOCK.exists():
        raise ValueError('Source lock already exists; refusing to overwrite')
    verified = verify_staged_source(source, upstream_lock)
    if verified['source']['repository'] != CONFIG['repository']:
        raise ValueError('Wrong official source')
    previous = read(source / 'source_integrity_manifest.json')
    if previous['source_files_sha256'] != verified['source_files_sha256']:
        raise ValueError('Official snapshot differs from its original verified manifest')
    lock = dict(repository=verified['source']['repository'], revision=verified['source']['revision'],
                directory=str(source.resolve()), verified_at=now(), config=read(source / 'config.json'),
                upstream_lock_sha256=digest(upstream_lock_path),
                files={name: dict(bytes=(source / name).stat().st_size, sha256=sha)
                       for name, sha in verified['source_files_sha256'].items()})
    write(SOURCE_LOCK, lock)
    preservation = snapshot_protected()
    power = read_gpu_power(CONFIG['gpu_power'])
    write(WORK / 'preparation.json', dict(status='COMPLETE', source_lock=str(SOURCE_LOCK),
          source_lock_sha256=digest(SOURCE_LOCK), preservation=preservation, gpu_power=power,
          downloaded_model_files=False, verified_at=now()))
    print('Existing official 4B source verified; protected inputs frozen; 150 W cap confirmed', flush=True)


if __name__ == '__main__':
    main()

