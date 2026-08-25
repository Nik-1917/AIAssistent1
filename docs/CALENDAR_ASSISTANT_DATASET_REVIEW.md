# Ручная вычитка датасета календарного ассистента

## Назначение

Исходные `.jsonl` предназначены для программ: одна JSON-запись на строку.
Их не нужно превращать в красивый формат и затем обучать модель по Markdown.
Для вычитки используется отдельная генерируемая витрина. Она показывает
system context, фразы пользователя и ожидаемый JSON отдельными блоками.

Витрина записывается в `build/calendar_sft_review/`. Папка `build/` не входит
в Git и не является источником обучения.

## Первый запуск

Откройте PowerShell в корне проекта и выполните:

```powershell
python -B tools\calendar_sft\render_dataset_review.py --split all
```

Будут созданы:

```text
build\calendar_sft_review\index.md
build\calendar_sft_review\train\page-001.md ...
build\calendar_sft_review\validation\page-001.md ...
build\calendar_sft_review\holdout\page-001.md
build\calendar_sft_review\review_manifest.json
```

Откройте сначала `index.md` в Android Studio или любом Markdown-просмотрщике.
В нём есть число записей, категории и ссылки на страницы. По умолчанию одна
страница содержит 50 примеров.

## Что проверять в каждой записи

1. В `SYSTEM` убедитесь, что дата, время и `Europe/Samara` соответствуют
   вычисленным параметрам.
2. В `USER` проверьте естественность русского текста и отсутствие личных
   данных реальных людей.
3. В `ОЖИДАЕМЫЙ JSON` проверьте `intent`, форму `reply` и только нужные
   поля в `params`.
4. Для поиска проверьте включаемую `range_start` и исключаемую `range_end`.
   «Сегодня» и «на этой неделе» начинаются с текущего времени system context;
   будущие дни начинаются в `00:00`.
5. Проверяйте train и validation раздельно. Holdout предназначен только для
   независимой итоговой оценки и не добавляется в обучение.

## Работа небольшими страницами

Для 25 записей на страницу:

```powershell
python -B tools\calendar_sft\render_dataset_review.py --split train --page-size 25 --output-dir build\calendar_sft_review_train_25
```

Для просмотра одной категории можно повторять `--category`:

```powershell
python -B tools\calendar_sft\render_dataset_review.py --split train --category calendar_search --category calendar_update --output-dir build\calendar_sft_review_commands
```

## Как вносить исправления

Не редактируйте файлы внутри `build/calendar_sft_review/`: следующий рендер
перезапишет их.

- Если источник указан как `calendar_assistant_train_seed.jsonl` или
  `calendar_assistant_eval_seed.jsonl`, исправьте эту конкретную JSONL-строку.
- Если ошибка повторяется в `calendar_assistant_candidates`, исправьте шаблон
  в `tools/generate_calendar_training_dataset.py`, пересоздайте candidates и
  проверьте получившийся diff перед обучением.
- Если ошибка в holdout, исправьте только holdout. Его нельзя копировать в
  train или validation.

После любого исправления пересоберите витрину:

```powershell
python -B tools\calendar_sft\render_dataset_review.py --split all --overwrite
```

Параметр `--overwrite` удаляет только ранее созданную витрину по указанному
`--output-dir`; исходные JSONL он не затрагивает.

## Обязательные проверки перед обучением

```powershell
python -B tools\calendar_sft\test_dataset_contract.py
python -B tools\calendar_sft\test_dataset_provenance.py
python -B tools\calendar_sft\test_search_periods.py
python -B tools\calendar_sft\prepare_dataset.py --check-only
```

Если все команды завершились без ошибки, витрина соответствует текущим
проверяемым источникам. Затем можно собирать обучающие артефакты отдельной
командой подготовки датасета.
