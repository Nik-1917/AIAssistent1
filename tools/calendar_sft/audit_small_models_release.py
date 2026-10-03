"""Verify completed compact releases and add explicit limits to their report."""
from __future__ import annotations

from collections import Counter
import json
import math
from pathlib import Path
import random

from dataset_contract import load_jsonl
from small_models_v2_common import DATA, NAMES, ROOT, WORK, digest, freeze_inputs, now, read, selection_score, write
from v12_63_evaluation import case_from_row, grade, summary

REPORT = ROOT / 'docs/CALENDAR_ASSISTANT_SMALL_MODELS_RESULTS.md'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def counts(records):
    metrics = ('passed', 'json_valid', 'contract_valid', 'intent_match', 'params_exact',
               'params_case_insensitive', 'temporal_params_match', 'reply_clock_correct')
    return {'total': len(records), **{key: sum(row[key] is True for row in records) for key in metrics},
            'reply_clock_eligible': sum(row['reply_clock_correct'] is not None for row in records)}


def exposure(config, selected_step):
    rows = load_jsonl(DATA / 'train.jsonl')
    step, presentations, seen = 0, 0, set()
    for epoch in range(config['epochs']):
        order = list(range(len(rows)))
        random.Random(config['seed'] + epoch).shuffle(order)
        for start in range(0, len(order), config['effective_batch_size']):
            if step == selected_step:
                break
            indices = order[start:start + config['effective_batch_size']]
            seen.update(indices)
            presentations += len(indices)
            step += 1
        if step == selected_step:
            break
    require(step == selected_step, 'Selected step is outside the configured run')
    return {'selected_step': step, 'presentations': presentations, 'unique_train_rows': len(seen),
            'total_train_rows': len(rows), 'all_training_rows_seen': len(seen) == len(rows),
            'categories_seen': dict(Counter(rows[index].get('category', 'unspecified') for index in seen))}


def verify_evaluation(key, inputs, input_hash, model_hash):
    directory = WORK / (key + '_evaluation')
    report = read(directory / 'report.json')
    binding = read(directory / 'binding.json')
    require(report['execution_status'] == 'COMPLETE', key + ': incomplete evaluation')
    require(binding['inputs_sha256'] == input_hash and binding['model_sha256'] == model_hash,
            key + ': invalid evaluation binding')
    records = []
    for case in inputs['cases']:
        prompt_path = WORK / 'evaluation_inputs/prompts' / (case['id'] + '.txt')
        require(digest(prompt_path) == case['prompt_sha256'], key + ': prompt changed')
        raw = read(directory / 'cases' / (case['id'] + '.json'))
        require(raw['model_sha256'] == model_hash and raw['prompt_sha256'] == case['prompt_sha256'],
                key + ': response binding changed')
        require(raw['output'] == raw['raw_response']['content'].strip(), key + ': response was repaired')
        records.append(grade(case, raw['output']))
    require(records == report['cases'], key + ': published grading differs from saved raw responses')
    require(summary(records)['groups'] == report['groups'], key + ': group totals differ')
    clock = [row for row in records if row['suite'] == 'known_heldout_input_clock']
    android_clock = [row for row in records if row['suite'] in ('known_heldout_android_prompt', 'additional_android_clock')]
    require(len(clock) == 72 and len(android_clock) == 72, 'Unexpected clock comparison groups')
    known_clock_ids = {case['id'] for case in inputs['cases'] if case['clocks']}

    def clock_counts(group):
        return dict(counts(group), known_clock_queries=sum(row['case_id'] in known_clock_ids for row in group),
                    invented_time_in_reply=sum(row.get('reply_error') == 'unknown time invented in reply' for row in group))

    return records, {'primary': counts(records[:231]), 'all': counts(records),
                     'clock_72': clock_counts(clock), 'android_clock_72': clock_counts(android_clock)}


def verify_selection(key, training):
    folder = WORK / key
    config = training['config']
    require(training['status'] == 'COMPLETE', key + ': training is incomplete')
    require(training['completed_optimizer_steps'] == config['planned_optimizer_steps'] ==
            config['epochs'] * math.ceil(config['train_rows'] / config['effective_batch_size']),
            key + ': planned steps are incomplete')
    for name, expected_hash in training['frozen_inputs'].items():
        require(digest(name) == expected_hash, 'Frozen training input changed: ' + name)
    development_rows = read(DATA / 'generation_dev.json')
    development_cases = [case_from_row(row, row.get('case_id', f'DEV_{index}'),
                        'clock' if row.get('category') == 'v12_63_dev' else
                        'legacy_' + json.loads(row['messages'][-1]['content'])['intent'])
                        for index, row in enumerate(development_rows)]
    best, best_step = None, None
    for step in config['selection_steps']:
        checkpoint = folder / f'checkpoint-{step:04}'
        integrity = read(checkpoint / 'checkpoint_integrity.json')
        adapter_hash = digest(checkpoint / 'adapter/adapter_model.safetensors')
        require(adapter_hash == integrity['adapter_sha256'], key + ': development adapter changed')
        records = []
        for case in development_cases:
            saved = read(checkpoint / 'development_cases' / (case['id'] + '.json'))
            require(saved['case'] == case and saved['adapter_sha256'] == adapter_hash,
                    key + ': development response binding changed')
            records.append(grade(case, saved['raw_output']))
        score = selection_score(development_cases, records)
        published = read(checkpoint / 'development.json')
        require(published['score'] == score and published['cases'] == records,
                key + ': development scoring changed')
        if best is None or tuple(score) > tuple(best):
            best, best_step = score, step
    selected = read(folder / 'selection.json')
    require(selected['status'] == 'COMPLETE' and selected['best_step'] == best_step and selected['best_score'] == best,
            key + ': selected adapter does not follow the declared criterion')
    adapter_hash = digest(folder / 'adapter/adapter_model.safetensors')
    require(adapter_hash == selected['adapter_sha256'] == training['adapter_sha256'] ==
            read(folder / f'checkpoint-{best_step:04}/checkpoint_integrity.json')['adapter_sha256'],
            key + ': selected adapter differs from its checkpoint')
    return exposure(config, best_step)


def main():
    manifest_path = WORK / 'release_manifest.json'
    manifest = read(manifest_path)
    require(manifest['execution_status'] in ('COMPLETE', 'MODELS_COMPLETE_PHONE_PENDING'), 'Release is incomplete')
    frozen = freeze_inputs()
    inputs_path = WORK / 'evaluation_inputs/inputs.json'
    inputs = read(inputs_path)
    require(len(inputs['cases']) == 297, 'Unexpected regression set')
    input_hash = digest(inputs_path)
    baseline_binding = read(WORK / 'baseline_evaluation/binding.json')
    require(digest(baseline_binding['model']) == baseline_binding['model_sha256'], 'Baseline GGUF changed')
    baseline, baseline_counts = verify_evaluation('baseline', inputs, input_hash, baseline_binding['model_sha256'])
    output = {'status': 'COMPLETE', 'verified_at': now(), 'protected_files': len(frozen['files']),
              'baseline': baseline_counts, 'models': {}, 'physical_device_inference': 'NOT_RUN'}
    lines = ['## Дополнительная проверка выпуска', '',
             'Показатель «все автопроверки» в таблице выше означает прохождение автоматических проверок: '
             'JSON, контракт, intent, параметры (регистр игнорируется только для `title` и `query`) и применимые проверки произношения времени. '
             'Он не подтверждает смысл каждого свободного ответа `reply`: например, для обычного `chat` '
             'проверка не сопоставляет содержание ответа с эталоном. Отдельно приведены сырые ответы на исходный запрос о 12:15.', '',
             '| Модель | Временные параметры, 72 | Время в reply, 72 | Временные параметры с промптом приложения, 72 | Время в reply с промптом приложения, 72 |',
             '|---|---:|---:|---:|---:|']

    def clock_line(name, metrics):
        standard, android = metrics['clock_72'], metrics['android_clock_72']
        return (f'| {name} | {standard["temporal_params_match"]}/72 | '
                f'{standard["reply_clock_correct"]}/{standard["known_clock_queries"]} | '
                f'{android["temporal_params_match"]}/72 | '
                f'{android["reply_clock_correct"]}/{android["known_clock_queries"]} |')

    lines.append(clock_line('V12.63 4B', baseline_counts))
    details = []
    for key, name in NAMES.items():
        training = read(WORK / key / 'training.json')
        selected_exposure = verify_selection(key, training)
        optimizer_rows = [json.loads(line) for line in (WORK / key / 'optimizer_steps.jsonl').read_text(encoding='utf-8').splitlines()]
        require([row['step'] for row in optimizer_rows] == list(range(1, training['completed_optimizer_steps'] + 1)),
                key + ': successful optimizer history has omissions or duplicates')
        require(all(math.isfinite(row[field]) for row in optimizer_rows for field in ('loss', 'gradient_norm')),
                key + ': optimizer history contains non-finite values')
        overflow_count = sum(row.get('overflow_retries', 0) for row in optimizer_rows)
        retry_path = WORK / key / 'amp_overflow_retries.jsonl'
        retries = [json.loads(line) for line in retry_path.read_text(encoding='utf-8').splitlines()] if retry_path.exists() else []
        require(len(retries) == overflow_count, key + ': AMP retry log differs from completed updates')
        artifact = read(WORK / key / 'release/gguf_manifest.json')['model']
        path = Path(artifact['file'])
        require(path.stat().st_size == artifact['bytes'] and digest(path) == artifact['sha256'], key + ': GGUF changed')
        export_repair = None
        artifact_manifest = read(WORK / key / 'release/gguf_manifest.json')
        if 'export_repair' in artifact_manifest:
            require(digest(artifact_manifest['export_repair']) == artifact_manifest['export_repair_sha256'],
                    key + ': export repair evidence changed')
            export_repair = read(artifact_manifest['export_repair'])
            require(export_repair['status'] == 'COMPLETE' and
                    export_repair['identical_f16_tensor_payloads'] == artifact['tensor_count'] and
                    export_repair['new_block_count'] == 24 and export_repair['new_mtp_layers'] == 0 and
                    export_repair['new_artifact'] == artifact and
                    export_repair['loader_smoke']['status'] == 'COMPLETE' and
                    export_repair['loader_smoke']['model_sha256'] == artifact['sha256'] and
                    export_repair['adapter_sha256'] == training['adapter_sha256'],
                    key + ': invalid text export repair')
            require(digest(export_repair['preserved_old_artifact']) == export_repair['old_artifact']['sha256'],
                    key + ': failed export history changed')
        records, metrics = verify_evaluation(key, inputs, input_hash, artifact['sha256'])
        critical = [row for row in records if row['case_id'] in ('OLD_M15', 'OLD_A_M15')]
        require(len(critical) == 2, 'Original 12:15 regression probes are missing')
        guard_path = WORK / key / 'cuda_memory_guard.json'
        guard = read(guard_path) if guard_path.exists() else None
        if guard is not None:
            require(digest(guard['trainer']) == guard['trainer_sha256'] and
                    digest(guard.get('wrapper', ROOT / 'tools/calendar_sft/train_small_model_memory_guard.py')) == guard['wrapper_sha256'],
                    key + ': recorded memory guard source changed')
        output['models'][key] = {'artifact': artifact, 'metrics': metrics, 'selected_exposure': selected_exposure,
                                'original_12_15_probes': critical,
                                'cuda_memory_guard': guard,
                                'gguf_export_repair': export_repair,
                                'successful_optimizer_steps':len(optimizer_rows),'amp_overflow_retries':overflow_count,
                                'free_reply_semantics_exhaustively_verified': False}
        lines.append(clock_line(name, metrics))
        details += ['', f'### {name}: выбранные веса и исходный запрос', '',
                    f'Выбранный адаптер шага {selected_exposure["selected_step"]} видел '
                    f'{selected_exposure["unique_train_rows"]} из {selected_exposure["total_train_rows"]} '
                    f'уникальных учебных записей ({selected_exposure["presentations"]} предъявлений в успешных обновлениях).',
                    f'В завершённом запуске {len(optimizer_rows)} последовательных обновлений без пропусков; численных повторов AMP: {overflow_count}.']
        for case in critical:
            details += ['', f'`{case["case_id"]}` — {case["user"]}', '',
                        f'Системный контекст проверки: `{case["system"]}`.', '', 'Ожидаемые параметры:', '```json',
                        json.dumps(case['expected']['params'], ensure_ascii=False, indent=2), '```',
                        'Сырой ответ GGUF:', '```text', case['raw'], '```']
    lines += details + ['',
              'Время в `reply` оценивается по часам modulo 12 и произношению; правильность даты и полного '
              '24-часового значения оценивается в параметрах. Знаменатель reply фиксирован по запросам '
              'с заданным эталонным временем. Выдуманное время в ответе на неоднозначный запрос отдельно '
              'учитывается как ошибка в общем результате; соответствующий счётчик `invented_time_in_reply` '
              'сохранён в `release_audit.json`.', '',
              'Полное покрытие 1 440 значений в обучающем корпусе не является проверкой обобщения. '
              'Новые компактные GGUF не проходили отдельный прогон всех 1 440 значений. '
              '694 validation-записи использовались для проверки данных и токенизации; выбор адаптера '
              'проводился по отдельным 48 development-запросам.', '',
              'Qwen3-1.7B продолжена с checkpoint 440 после намеренного завершения замедлившегося процесса. '
              'Адаптер, optimizer, scheduler и RNG восстановлены из проверенного состояния. Для продолжения '
              'и обучения Qwen3.5 использован отдельный wrapper с долей памяти CUDA 0,8 и сборкой '
              'неиспользуемого кеша при пороге 0,8. История до продолжения находится в `before_cuda_memory_guard`.', '',
              'Первая попытка Qwen3.5 остановилась перед обновлением 45 из-за нечисловых градиентов FP16; '
              'она сохранена в `qwen35_2b_failed_step0045`. Итоговая Qwen3.5 обучена заново отдельным '
              'тренером с начальным AMP scale 128. При переполнении GradScaler пропускает повреждённое '
              'обновление, снижает scale, а тот же пакет повторяется с восстановлением RNG. Примеры, '
              'scheduler и номер шага продвигаются только после конечных градиентов. Сохранение — каждые '
              '44 шага; выбор — по тем же шести development-проверкам. Две CUDA-проверки подтвердили '
              'единственное реальное обновление, сохранение dropout/RNG и отсутствие изменения весов '
              'при исчерпании попыток. Данные, эффективный batch 16, lr 0,0001 и три эпохи сохранены.', '',
              'Первый экспорт Qwen3.5 сохранил в метаданных лишний MTP-блок: были объявлены 25 блоков '
              'при наличии 24 текстовых слоёв. Загрузчик отказался открывать файл до генерации ответов. '
              'Повторный экспорт использует официальный параметр `--no-mtp`; исходные merged-веса, '
              'конфигурация, адаптер и данные сохранены. Побайтно совпали все 320 payload тензоров F16. '
              'Исправленный Q4_K_M прошёл отдельную загрузку и последующую полную регрессионную проверку. '
              'Первый GGUF и журналы ошибки сохранены в `release_failed_mtp` и '
              '`qwen35_2b_evaluation_failed_mtp`; подробности и хеши — в `build_text_only/repair.json`.', '',
              'Скорость и расход памяти на телефоне не измерялись. Время desktop-проверок нельзя считать '
              'контролируемым сравнением скорости: часть вычислений выполнялась одновременно с обучением второй модели.', '']
    audit_path = WORK / 'release_audit.json'
    require(not audit_path.exists(), 'Release audit already exists; inspect it before replacing')
    write(audit_path, output)
    original_report = REPORT.read_text(encoding='utf-8')
    original_manifest = manifest_path.read_text(encoding='utf-8')
    for filename, text in (('driver_report.md', original_report), ('driver_release_manifest.json', original_manifest)):
        with (WORK / filename).open('x', encoding='utf-8') as handle:
            handle.write(text)
    clarified_report = original_report.replace('Полностью верно, 231', 'Все автопроверки, 231')
    REPORT.write_text(clarified_report + '\n' + '\n'.join(lines), encoding='utf-8')
    manifest.update(report_sha256=digest(REPORT), release_audit=str(audit_path), release_audit_sha256=digest(audit_path))
    write(manifest_path, manifest)
    print('Release audit complete; original driver report and manifest preserved.')


if __name__ == '__main__':
    main()
