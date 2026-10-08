"""Verify 1.7B artifacts and compare all three sizes on bound, identical cases."""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path

from run_context import ROOT, RUN, WORK, CONFIG, read, write, digest, now, verify_preservation
from gpu_power import read_gpu_power, validate_snapshot


def metrics(report):
    keys = ('passed', 'json_valid', 'contract_valid', 'params_exact', 'intent_match',
            'offset_arithmetic_correct', 'reply_clock_correct', 'reply_params_consistent')
    return {key: dict(passed=sum(case[key] is True for case in report['cases']),
                      evaluated=sum(case[key] is not None for case in report['cases']))
            for key in keys}


def main():
    training = read(RUN / 'training.json')
    manifest = read(RUN / 'release/gguf_manifest.json')
    comparison = read(RUN / 'quantization_comparison.json')
    validation = read(RUN / 'validation.json')
    if training['status'] != 'COMPLETE' or training['completed_optimizer_steps'] != 1128:
        raise ValueError('Training incomplete')
    for path, expected in training['frozen_inputs'].items():
        if digest(path) != expected:
            raise ValueError('Frozen training input changed: ' + path)
    if digest(RUN / 'adapter/adapter_model.safetensors') != training['adapter_sha256']:
        raise ValueError('Selected adapter changed')
    for key in ('model', 'intermediate'):
        artifact = manifest[key]
        path = Path(artifact['file'])
        if path.stat().st_size != artifact['bytes'] or digest(path) != artifact['sha256']:
            raise ValueError('GGUF artifact changed')
    binding = read(WORK / 'comparison_baseline.json')
    for path, expected in binding['files_sha256'].items():
        if digest(path) != expected:
            raise ValueError('Bound baseline evidence changed: ' + path)
    folders = [('17b', RUN),
               ('06b', ROOT / 'build/calendar_v12_66_qwen3_06b/qwen3_0_6b'),
               ('4b', ROOT / 'build/calendar_v12_66_qwen3_4b/qwen3_4b')]
    reports = {}
    reference = None
    for prefix, directory in folders:
        for kind in ('f16', 'q4'):
            folder = directory / ('evaluation_' + kind)
            report = read(folder / 'report.json')
            inputs = read(folder / 'inputs.json')
            if reference is None:
                reference = inputs
            if inputs['packs'] != reference['packs'] or inputs['decoding'] != reference['decoding']:
                raise ValueError('Different evaluation prompts or decoding')
            if report['execution_status'] != 'COMPLETE' or report['total'] != 200 or len(report['cases']) != 200:
                raise ValueError('Evaluation incomplete')
            if report['model_sha256'] != inputs['model_sha256'] or inputs['grammar_constraint']:
                raise ValueError('Invalid evaluation model or grammar binding')
            reports[prefix + '_' + kind] = report
    preservation = verify_preservation()
    samples = [validate_snapshot(json.loads(line), CONFIG['gpu_power'])
               for path in RUN.glob('*gpu_power_samples.jsonl')
               for line in path.read_text(encoding='utf-8').splitlines()]
    if not samples:
        raise ValueError('GPU power evidence absent')
    power = dict(constraint=CONFIG['gpu_power'], sample_count=len(samples),
                 maximum_observed_limit_watts=max(s['limit_watts'] for s in samples),
                 maximum_observed_temperature_c=max(s['temperature_c'] for s in samples),
                 current=read_gpu_power(CONFIG['gpu_power']))
    evidence = {'training.json': RUN / 'training.json', 'gguf_manifest.json': RUN / 'release/gguf_manifest.json',
                'evaluation_f16/report.json': RUN / 'evaluation_f16/report.json',
                'evaluation_q4/report.json': RUN / 'evaluation_q4/report.json',
                'quantization_comparison.json': RUN / 'quantization_comparison.json'}
    quality = dict(execution_status='COMPLETE', quality_status=reports['17b_q4']['quality_status'],
                   verified_at=now(), model=manifest['model'], source_repository=manifest['source_repository'],
                   source_revision=manifest['source_revision'], selected_step=training['selected_step'],
                   completed_optimizer_steps=1128, metrics={k: metrics(v) for k, v in reports.items()},
                   preservation=preservation, gpu_power=power, same_prompts_and_decoding=True,
                   validation=validation, evidence_sha256={k: digest(v) for k, v in evidence.items()},
                   baseline_binding=binding, training_frozen_inputs_checked=len(training['frozen_inputs']),
                   timing=dict(optimizer_seconds=training['optimizer_seconds'],
                               completed_development_segment_seconds=training['completed_development_segment_seconds'],
                               training_seconds=training['training_seconds']),
                   physical_device_inference='NOT_RUN')
    write(RUN / 'release/quality_manifest.json', quality)
    timestamp = datetime.now(timezone.utc).astimezone(timezone(timedelta(hours=4))).isoformat(timespec='seconds')
    artifact = manifest['model']
    lines = ['# V12.66 Qwen3-1.7B: результаты обучения и сравнения', '',
             'Отчёт составлен ' + timestamp + ' (Самара, UTC+4).', '',
             'Выполнение — COMPLETE; качество — ' + quality['quality_status'] + '.', '',
             '## Обучение и модель', '',
             '- Источник: ' + manifest['source_repository'] + ', ревизия `' + manifest['source_revision'] + '`.',
             '- 6009 train, 762 validation, три эпохи и 1128 обновлений; данные V12.66 сохранены.',
             '- Выбран шаг ' + str(training['selected_step']) + ' по разработческим запросам; holdout не использовался.',
             '- GGUF: `' + artifact['file'] + '`.',
             '- Размер: ' + str(artifact['bytes']) + ' байт; SHA-256: `' + artifact['sha256'] + '`.',
             '- Слияние, конечность тензоров и загрузка GGUF проверены; qwen3, 28 слоёв.', '',
             '## Сравнение', '',
             '100 отложенных вопросов в двух форматах: по 200 вариантов для каждого GGUF.',
             'Запросы и декодирование совпадают; JSON-грамматика и исправление ответов не использовались.', '',
             '| Проверка | 1.7B F16 | 1.7B Q4_K_M | 0.6B F16 | 0.6B Q4_K_M | 4B F16 | 4B Q4_K_M |',
             '| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    labels = [('passed', 'Полное прохождение'), ('json_valid', 'Корректный JSON'), ('intent_match', 'Правильный intent'),
              ('contract_valid', 'JSON-контракт'), ('params_exact', 'Точные параметры'),
              ('offset_arithmetic_correct', 'Арифметика относительного времени'),
              ('reply_clock_correct', 'Произнесённое время'), ('reply_params_consistent', 'Согласованность reply и params')]
    for key, label in labels:
        values = [quality['metrics'][name][key] for name in ('17b_f16', '17b_q4', '06b_f16', '06b_q4', '4b_f16', '4b_q4')]
        lines.append('| ' + label + ' | ' + ' | '.join(str(v['passed']) + '/' + str(v['evaluated']) for v in values) + ' |')
    lines += ['', 'Эти результаты относятся к указанному набору и не являются общей точностью приложения.', '',
              '## Мощность и сохранность', '',
              '- Подтверждён лимит 150 Вт: 60% штатных 250 Вт.',
              '- Измерений: ' + str(len(samples)) + '; максимальный наблюдавшийся лимит: ' + str(power['maximum_observed_limit_watts']) + ' Вт.',
              '- Защищённых файлов Android, данных и правил проверено: ' + str(preservation['files_checked']) + '.',
              '- Замороженных входов обучения проверено: ' + str(len(training['frozen_inputs'])) + '.',
              '- Тесты отдельного запуска: ' + str(validation['tests_passed']) + '/' + str(validation['tests_run']) + '.',
              '- Проверка учебных входов V12.66 прошла; исторический снимок и тесты сохранены.',
              '- Физический телефон: запуск модели в приложении не выполнялся.', '',
              '## Доказательства', '',
              'Контрольные точки, журналы и сырые ответы сохранены в `' + str(RUN) + '`.',
              '`release/quality_manifest.json` связывает результаты с весами и журналами.', '']
    report_path = ROOT / 'docs/CALENDAR_ASSISTANT_V12_66_QWEN3_17B_RESULTS.md'
    with report_path.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write('\n'.join(lines))
    print('1.7B, 0.6B and 4B comparison saved: ' + str(report_path), flush=True)


if __name__ == '__main__':
    main()
