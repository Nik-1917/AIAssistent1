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
   Сегодня и на этой неделе начинаются с текущего времени system context;
   будущие дни начинаются в `00:00`.
5. Для `value` проверьте целое число без валюты и дробной части. Для
   `calendar_sum` проверьте только фильтр и период: модель не должна печатать
   вычисленную сумму.
6. Для каждого `calendar_add` проверьте `date` или `starts_at`. Если дата не
   названа, точное время позже system time означает сегодня, а более раннее или
   равное завтра. Без точного времени используется сегодняшняя `date`.
7. Убедитесь, что `reply` не спрашивает недостающие данные. Остальные неизвестные
   поля должны отсутствовать, а не получать `null`, пустую строку или default
   модели.
8. В пользовательском тексте, `reply` и строковых параметрах не допускаются
   Unicode U+2014, U+00AB и U+00BB.
9. Проверяйте train и validation раздельно. Holdout предназначен только для
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

- Если источник указан как seed или `calendar_assistant_manual_*_v5.jsonl`,
  исправьте конкретную JSONL-строку вручную.
- Для текущей v5-ревизии не запускайте
  `tools/generate_calendar_training_dataset.py`. Проверенные candidate-файлы
  очищены от старых уточняющих строк вручную; новые v5-примеры находятся только
  в ручных источниках.
- Генератор поддерживает актуальный контракт как reference implementation. Его
  будущий запуск и полная замена candidate-файлов требуют отдельного решения,
  просмотра всего diff и новой ручной вычитки.
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

`calendar_sft_data_provenance_v4.json` содержит хеши прежних v4-артефактов и не
подтверждает изменённые v5-источники. До обучения нужно отдельно собрать и
вычитать v5-артефакты, создать новый register из
`tools/calendar_sft/dataset_provenance.template.json`, записать точные SHA-256
train/validation и получить решение правообладателя со статусом `VERIFIED`.
