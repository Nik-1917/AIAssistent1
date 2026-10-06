"""Verify completed artifacts and write the approved comparison with Qwen3-0.6B."""
from datetime import datetime, timedelta, timezone
import json

from run_context import ROOT, RUN, WORK, CONFIG, read, write, digest, now, verify_preservation
from gpu_power import read_gpu_power, validate_snapshot


def metrics(report):
    keys = ('passed', 'json_valid', 'contract_valid', 'params_exact', 'intent_match',
            'offset_arithmetic_correct', 'reply_clock_correct', 'reply_params_consistent')
    return {key: dict(passed=sum(case[key] is True for case in report['cases']),
                      evaluated=sum(case[key] is not None for case in report['cases']))
            for key in keys}


def ratio(report, key):
    metric = metrics(report)[key]
    return str(metric['passed']) + '/' + str(metric['evaluated'])


def main():
    training = read(RUN / 'training.json')
    validation = read(RUN / 'validation.json')
    manifest = read(RUN / 'release/gguf_manifest.json')
    comparison = read(RUN / 'quantization_comparison.json')
    if training['status'] != 'COMPLETE' or training['completed_optimizer_steps'] != 1128:
        raise ValueError('Training incomplete')
    for path, expected in training['frozen_inputs'].items():
        if digest(path) != expected:
            raise ValueError('Frozen training input changed: ' + path)
    if digest(RUN / 'adapter/adapter_model.safetensors') != training['adapter_sha256']:
        raise ValueError('Selected adapter changed')
    for key in ('model', 'intermediate'):
        artifact = manifest[key]
        from pathlib import Path
        path = Path(artifact['file'])
        if path.stat().st_size != artifact['bytes'] or digest(path) != artifact['sha256']:
            raise ValueError('GGUF artifact changed')
    baseline = ROOT / 'build/calendar_v12_66_qwen3_06b/qwen3_0_6b'
    baseline_binding = read(WORK / 'comparison_baseline.json')
    for path, expected in baseline_binding['files_sha256'].items():
        if digest(path) != expected:
            raise ValueError('Bound baseline evidence changed: ' + path)
    reports = {}
    for prefix, directory in (('4b', RUN), ('06b', baseline)):
        for kind in ('f16', 'q4'):
            reports[prefix + '_' + kind] = read(directory / ('evaluation_' + kind) / 'report.json')
            inputs = read(directory / ('evaluation_' + kind) / 'inputs.json')
            if prefix == '4b' and kind == 'f16':
                reference = inputs
            if inputs['packs'] != reference['packs'] or inputs['decoding'] != reference['decoding']:
                raise ValueError('Models were evaluated on different prompts or decoding')
            if reports[prefix + '_' + kind]['execution_status'] != 'COMPLETE':
                raise ValueError('Evaluation incomplete')
    preservation = verify_preservation()
    samples = []
    for path in RUN.glob('*gpu_power_samples.jsonl'):
        for line in path.read_text(encoding='utf-8').splitlines():
            samples.append(validate_snapshot(json.loads(line), CONFIG['gpu_power']))
    if not samples:
        raise ValueError('GPU power evidence is absent')
    power = dict(constraint=CONFIG['gpu_power'], sample_count=len(samples),
                 maximum_observed_limit_watts=max(sample['limit_watts'] for sample in samples),
                 maximum_observed_temperature_c=max(sample['temperature_c'] for sample in samples),
                 current=read_gpu_power(CONFIG['gpu_power']))
    evidence_paths = {
        'training.json':RUN/'training.json', 'gguf_manifest.json':RUN/'release/gguf_manifest.json',
        'evaluation_f16/report.json':RUN/'evaluation_f16/report.json',
        'evaluation_q4/report.json':RUN/'evaluation_q4/report.json',
        'quantization_comparison.json':RUN/'quantization_comparison.json'}
    quality = dict(execution_status='COMPLETE', quality_status=reports['4b_q4']['quality_status'],
                   verified_at=now(), model=manifest['model'], source_repository=manifest['source_repository'],
                   source_revision=manifest['source_revision'], selected_step=training['selected_step'],
                   completed_optimizer_steps=1128, metrics={name:metrics(report) for name,report in reports.items()},
                   preservation=preservation, gpu_power=power, same_prompts_and_decoding=True,
                   validation=validation,
                   evidence_sha256={name:digest(path) for name,path in evidence_paths.items()},
                   baseline_binding=baseline_binding,
                   training_frozen_inputs_checked=len(training['frozen_inputs']), physical_device_inference='NOT_RUN')
    write(RUN / 'release/quality_manifest.json', quality)
    labels = [('passed','Полное прохождение'),('json_valid','Корректный JSON'),('intent_match','Правильный intent'),
              ('contract_valid','JSON-контракт'),('params_exact','Точные параметры'),
              ('offset_arithmetic_correct','Арифметика относительного времени'),
              ('reply_clock_correct','Произнесённое время'),('reply_params_consistent','Согласованность reply и params')]
    timestamp = datetime.now(timezone.utc).astimezone(timezone(timedelta(hours=4))).isoformat(timespec='seconds')
    model = manifest['model']
    lines = ['# V12.66 Qwen3-4B: результаты обучения и сравнения', '',
             'Отчёт составлен ' + timestamp + ' (Самара, UTC+4).', '',
             'Состояние выполнения — COMPLETE; качество — ' + quality['quality_status'] + '.', '',
             '## Обучение и модель', '',
             '- Исходная модель: ' + manifest['source_repository'] + ', ревизия `' + manifest['source_revision'] + '`.',
             '- Исходные 6009 train и 762 validation V12.66; три эпохи, 1128 обновлений.',
             '- Выбран адаптер шага ' + str(training['selected_step']) + ' по разработческим запросам; holdout не использовался.',
             '- Файл: `' + model['file'] + '`.',
             '- Размер: ' + str(model['bytes']) + ' байт; SHA-256: `' + model['sha256'] + '`.',
             '- Слияние и загрузка GGUF проверены; архитектура qwen3, 36 слоёв.', '',
             '## Сравнение на одинаковых запросах', '',
             '100 отложенных вопросов в двух форматах prompt, по 200 вариантов для каждого GGUF.',
             'Prompts и параметры декодирования совпадают. JSON-грамматика и исправление ответов не использовались.', '',
             '| Проверка | 4B F16 | 4B Q4_K_M | 0.6B F16 | 0.6B Q4_K_M |',
             '| --- | ---: | ---: | ---: | ---: |']
    for key,label in labels:
        lines.append('| ' + label + ' | ' + ' | '.join(ratio(reports[name],key) for name in ('4b_f16','4b_q4','06b_f16','06b_q4')) + ' |')
    lines += ['', 'Полное прохождение относится к выбранному набору и настройкам; оно не является общей точностью приложения.', '',
              '## Ограничение мощности и сохранность', '',
              '- Подтверждён лимит 150 Вт: 60% штатных 250 Вт. Лимит автоматически не сбрасывался.',
              '- Сохранено ' + str(len(samples)) + ' измерений; максимальный наблюдавшийся лимит — ' + str(power['maximum_observed_limit_watts']) + ' Вт.',
              '- Проверено ' + str(preservation['files_checked']) + ' файлов Android, данных и правил: изменений нет.',
              '- Проверено ' + str(len(training['frozen_inputs'])) + ' замороженных входов обучения: изменений нет.',
              '- Прошли 11 тестов нового запуска и 21 из 22 существующих тестов данных/оценщика и CUDA AMP.',
              '- Исторический тест полной сборки V12.66 отклоняет прежние изменения Android; действующая проверка учебных входов прошла. Старый тест и снимок сохранены.',
              '- Физический телефон: запуск этой модели в приложении не выполнялся.', '',
              '## Доказательства', '',
              'Журналы, checkpoint, сырые ответы и хеши сохранены в `' + str(RUN) + '`.',
              '`release/quality_manifest.json` связывает результаты с проверенными GGUF и журналами.', '']
    report_path = ROOT / 'docs/CALENDAR_ASSISTANT_V12_66_QWEN3_4B_RESULTS.md'
    with report_path.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write('\n'.join(lines))
    print('Completed 4B results and comparison with 0.6B saved: ' + str(report_path), flush=True)


if __name__ == '__main__':
    main()
