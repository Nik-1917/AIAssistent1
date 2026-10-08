"""Reuse the hash-locked local Qwen3-1.7B source and bind prior comparisons."""
from pathlib import Path

from run_context import ROOT, WORK, CONFIG, SOURCE_LOCK, read, write, digest, now, snapshot_protected, verify_source
from gpu_power import read_gpu_power


def verify_bound_file(folder, name, item):
    path = (folder / name).resolve()
    if not path.is_relative_to(folder.resolve()) or not path.is_file():
        raise ValueError('Source file is outside the pinned directory: ' + name)
    actual = digest(path)
    if path.stat().st_size != item['bytes'] or actual != item['sha256']:
        raise ValueError('Source file changed: ' + name)
    if item.get('upstream_lfs_sha256') and actual != item['upstream_lfs_sha256']:
        raise ValueError('Source differs from the recorded upstream LFS hash: ' + name)


def bind_baselines():
    files = {}
    reference = None
    models = {
        '06b': ROOT / 'build/calendar_v12_66_qwen3_06b/qwen3_0_6b',
        '4b': ROOT / 'build/calendar_v12_66_qwen3_4b/qwen3_4b',
    }
    for model, folder in models.items():
        quality = read(folder / 'release/quality_manifest.json')
        for kind in ('f16', 'q4'):
            directory = folder / ('evaluation_' + kind)
            inputs = read(directory / 'inputs.json')
            report_path = directory / 'report.json'
            report = read(report_path)
            if report['execution_status'] != 'COMPLETE' or len(report['cases']) != 200:
                raise ValueError('Baseline evaluation incomplete: ' + model + '_' + kind)
            expected = quality['evidence_sha256']['evaluation_' + kind + '/report.json']
            if digest(report_path) != expected:
                raise ValueError('Baseline differs from its final verified manifest')
            if reference is None:
                reference = inputs
            if inputs['packs'] != reference['packs'] or inputs['decoding'] != reference['decoding']:
                raise ValueError('Baseline prompts or decoding differ')
            if inputs['decoding']['seed'] != CONFIG['seed'] or inputs['decoding']['n_predict'] != CONFIG['max_new_tokens']:
                raise ValueError('New configuration differs from the baseline decoding')
            if inputs['grammar_constraint']:
                raise ValueError('Baseline uses a JSON grammar')
            for path in (directory / 'inputs.json', report_path):
                files[str(path)] = digest(path)
    return dict(status='COMPLETE', verified_at=now(), models=list(models), files_sha256=files,
                same_200_prompt_variants=True, holdout_used_for_selection=False)


def main():
    previous_path = ROOT / 'build/calendar_small_models_20261003_v2/Qwen3-1.7B_source_lock.json'
    previous = read(previous_path)
    if previous['repository'] != CONFIG['repository'] or previous['revision'] != CONFIG['source_revision']:
        raise ValueError('Wrong pinned official model')
    folder = Path(previous['directory']).resolve()
    expected = (ROOT / 'build/calendar_sft_models/Qwen3-1.7B' / CONFIG['source_revision']).resolve()
    if folder != expected or not previous['files'] or previous.get('remote_code_executed'):
        raise ValueError('Invalid local source binding')
    if SOURCE_LOCK.exists() or (WORK / 'comparison_baseline.json').exists():
        raise ValueError('Refusing to replace existing source or baseline bindings')
    for name, item in previous['files'].items():
        verify_bound_file(folder, name, item)
    config = read(folder / 'config.json')
    if config != previous['config'] or config['model_type'] != 'qwen3' or config['num_hidden_layers'] != 28:
        raise ValueError('Unexpected source architecture')
    license_text = (folder / 'LICENSE').read_text(encoding='utf-8')
    if previous['license'] != 'Apache-2.0' or 'Apache License' not in license_text or 'Version 2.0' not in license_text:
        raise ValueError('Unexpected source license')
    baseline = bind_baselines()
    power = read_gpu_power(CONFIG['gpu_power'])
    lock = dict(previous, verified_at=now(), reused_from=str(previous_path),
                prior_lock_sha256=digest(previous_path), downloaded_model_files=False)
    write(SOURCE_LOCK, lock)
    write(WORK / 'comparison_baseline.json', baseline)
    preservation = snapshot_protected()
    verify_source(CONFIG['model'])
    write(WORK / 'preparation.json', dict(status='COMPLETE', source_lock=str(SOURCE_LOCK),
          source_lock_sha256=digest(SOURCE_LOCK), source_files_verified=len(previous['files']),
          preservation=preservation, gpu_power=power, baseline_files_bound=len(baseline['files_sha256']),
          downloaded_model_files=False, verified_at=now()))
    print('Local Qwen3-1.7B source, both baseline evaluations and 150 W cap verified', flush=True)


if __name__ == '__main__':
    main()
