# Аудит данных V12.6 Q4_K_M — 28 сентября 2026 года

Статус: анализ завершён; изменения обучающих данных, Android-кода, весов и правил не выполнялись. Этот файл фиксирует результаты проверки и предложение следующего этапа. Он не вводит новых правил работы ассистента.

Пользователь подтвердил выбранную версию: **12.6 Q4_K_M**. Конкретные неудачные запросы с телефона не предоставлены. Поэтому причину каждого наблюдаемого пользователем сбоя и текущую точность GGUF на телефоне этот аудит не устанавливает.

## Вывод

Требование одинакового количества строк во всех категориях не является достаточным условием качества. В этом проекте 12 порядков слов для интервалов уже имеют ровно по 20 обучающих примеров, а сохранённая проверка адаптера дала точное совпадение лишь для 8 из 24 соответствующих запросов.

Обнаружены более конкретные проблемы: часть эталонов противоречит правилу сохранения полного поискового запроса; старые контрольные ответы содержат ту же проблему; некоторые сочетания полей представлены несколькими строками; многочисленные перестановки опираются на небольшое число базовых сценариев; ввод Android отличается от обучающего. Влияние каждого фактора на качество Q4_K_M ещё не измерено.

## Проверенные источники и связь с обучением


- Данные: [train.jsonl](C:/Users/007/AndroidStudioProjects/AIAssistent1/docs/calendar_sft_order_coverage/train.jsonl) и [validation.jsonl](C:/Users/007/AndroidStudioProjects/AIAssistent1/docs/calendar_sft_order_coverage/validation.jsonl).

- Манифест обучения: [build/calendar_sft_qwen3_v12_6_epoch1_20260914/run_manifest.json](C:/Users/007/AndroidStudioProjects/AIAssistent1/build/calendar_sft_qwen3_v12_6_epoch1_20260914/run_manifest.json). SHA-256 текущих train/validation совпадают с файлами, использованными в этом запуске.

- Сохранённая проверка от 15 сентября: [build/calendar_sft_v12_6_holdout_20260915/current_contract_report.json](C:/Users/007/AndroidStudioProjects/AIAssistent1/build/calendar_sft_v12_6_holdout_20260915/current_contract_report.json) и [build/calendar_sft_v12_6_holdout_20260915/REPORT.md](C:/Users/007/AndroidStudioProjects/AIAssistent1/build/calendar_sft_v12_6_holdout_20260915/REPORT.md). Проверялся Transformers NF4 + LoRA, а не GGUF и не Android.

- Манифест GGUF: [build/calendar_sft_qwen3_v12_6_gguf_20260914/gguf_manifest.json](C:/Users/007/AndroidStudioProjects/AIAssistent1/build/calendar_sft_qwen3_v12_6_gguf_20260914/gguf_manifest.json). Поля inference_tests, holdout_evaluation и physical_device_validation в нём имеют значение NOT_RUN. Адаптер в нём совпадает по SHA-256 с адаптером сохранённой проверки.


| Файл | Строк | SHA-256 |
| --- | --- | --- |
| calendar_holdout_v12_2.jsonl | 8 | `504b86dfacc3bbc742e1007f389f60a2bd43f01d9e29a0a9d5f29fa3cee82ade` |
| half_hour_holdout.jsonl | 36 | `5d614f04f6d90333c7857b50833c20efebbf055262547d89086d16d8da56982c` |
| holdout.jsonl | 36 | `a70f5720cb03b2dbd6f1b57cb30f28cfa4e9da80ffba71a0d182402fb980be19` |
| interval_holdout.jsonl | 12 | `08532c3e0d667452bec2e7bded0804636bb2c08c29e9a585f68a5eeef3d930d1` |
| last_event_holdout.jsonl | 6 | `d7141c98bdc4a5310200062f64738f2ba768f5a14d9de3acadbcd8ea84b04b53` |
| order_holdout.jsonl | 43 | `6a28655f44e9024bc527e13d8a45f100a06c3e8ff3df19439888b33ce8d4f2ea` |
| regression_holdout.jsonl | 63 | `b2a7bdbc93a6f59769f0ab0a9108dc707e1bc7a494d00cbd5c8bb2fbfa7d1e34` |
| train.jsonl | 2938 | `2a7ee2cb7a0a1eaef8b4dd1f8a814918264686772c88c46fb9adddc2464db763` |
| validation.jsonl | 803 | `c5ddd75e29d8c8991f3c26cd5c5ad2aaf7498e8104a06e295daa74a8cbc9e0b9` |


## Распределение команд

Train содержит 2 938 строк, validation — 803, семь контрольных файлов — 204. В train 210 различных значений поля category; 53 из них представлены одной строкой. Самая большая категория содержит 264 строки. Это административные метки разных поколений данных, а не 210 равноценных навыков.


| Команда | Train | Доля train | Validation | Контроль | Токены ответа в train |
| --- | --- | --- | --- | --- | --- |
| calendar_add | 1729 | 58.85% | 453 | 155 | 114654 |
| calendar_update | 456 | 15.52% | 128 | 25 | 27617 |
| calendar_search | 323 | 10.99% | 97 | 14 | 24191 |
| chat | 216 | 7.35% | 61 | 1 | 9441 |
| calendar_delete | 127 | 4.32% | 32 | 5 | 8859 |
| calendar_sum | 87 | 2.96% | 32 | 4 | 6203 |


Создание событий встречается примерно в 19,9 раза чаще суммирования. Без статистики реальных запросов нельзя утверждать, что требуются равные доли команд или что именно эта пропорция вызвала плохой результат.

Поле category не передаётся модели: train_qlora.py токенизирует только messages. Переименование категорий само по себе не меняет обучение. В просмотренном скрипте нет балансировки выборки по category. Учитываются токены ответа assistant; токены system/user маскируются значением -100. В train 190 965 учитываемых токенов ответа, из них 60,04% относятся к calendar_add. Это подсчёт токенов, а не точная оценка вклада каждой команды в градиент.


Источник: [токенизация и маска](C:/Users/007/AndroidStudioProjects/AIAssistent1/tools/calendar_sft/train_qlora.py:134); [подготовка Dataset и Trainer](C:/Users/007/AndroidStudioProjects/AIAssistent1/tools/calendar_sft/train_qlora.py:379).


## Подтверждённые смысловые противоречия

Действующее описание требует сохранять полное смысловое название/описание цели в query и не отбрасывать значимые уточнения.


Правило только прочитано: [полные названия и поисковые запросы](C:/Users/007/AndroidStudioProjects/AIAssistent1/docs/CALENDAR_ASSISTANT_ANDROID_MECHANISMS.md:122).


| Расположение | Вход | Эталон | Проблема |
| --- | --- | --- | --- |
| [train:5](C:/Users/007/AndroidStudioProjects/AIAssistent1/docs/calendar_sft_order_coverage/train.jsonl:5) | Найди завтра встречи с Анной. | query: Анна | Потерян тип события: встречи. |
| [train:170](C:/Users/007/AndroidStudioProjects/AIAssistent1/docs/calendar_sft_order_coverage/train.jsonl:170) | Перенеси визит к подопечной из того месяца на первое число следующего месяца. | target.query: визит | Потеряно уточнение к подопечной. |
| [train:248](C:/Users/007/AndroidStudioProjects/AIAssistent1/docs/calendar_sft_order_coverage/train.jsonl:248) | Переименуй Визит к подопечной в Визит к подопечной Анне. | target.query: визит | Цель изменения сокращена. |
| [train:1338](C:/Users/007/AndroidStudioProjects/AIAssistent1/docs/calendar_sft_order_coverage/train.jsonl:1338) | Найди завтра осмотры пожарных датчиков второго корпуса. | query: осмотры пожарных датчиков второго корпуса | Этот пример сохраняет полную цель согласно правилу. |


Узкая проверка сочетаний «подопеч…/Анн…» и укороченных query обнаружила 12 таких обучающих строк. Это число совпадений конкретного фильтра, не полный подсчёт всех смысловых дефектов корпуса.

В контрольном случае H017 запрос «Через три дня посмотри визиты к подопечным» ожидает query: «визит». Модель ответила query: «визиты к подопечным» при совпадающих временных границах, и сохранённая оценка поставила ошибку. Полный query соответствует процитированному правилу. Следовательно, старый процент exact-match нельзя без смысловой ревизии считать точной оценкой выполнения действующих требований. Замена версии структурного валидатора на v12.57 не исправляет ошибочные смысловые эталоны автоматически.

Правила исправлять для подгонки под эти эталоны не требуется. Предлагается исправлять данные и версионировать контрольные ожидания отдельно, сохранив исторические файлы.

## Покрытие полей и сочетаний


| Признак в train | Количество | Основание для следующей проверки |
| --- | --- | --- |
| calendar_add с duration_min | 1145 | Различать длительность, время начала и value. |
| calendar_add с value | 70 | 70 из 1729 созданий; проверить ноль, отрицательные и отсутствующее значение. |
| calendar_add с ends_at | 256 | Проверить извлечение окончания и переход даты. |
| ends_at и value вместе | 6 | Эти шесть строк относятся к трём семействам: OP13, OP14, OP16. |
| ends_at и duration_min вместе | 2 | Обе строки — варианты одного семейства OP16, также содержащего value. |
| Интервалы с датой окончания позже даты начала | 43 | Наличие примеров есть; перенос навыка на новые сочетания проверен с ошибками. |
| calendar_update с changes.value | 22 | Отдельно от времени/длительности. |
| calendar_update с changes.clear_value | 17 | Отсутствие значения, ноль и очистка имеют разный смысл. |
| notes в params или changes | 0 | В текущем Android есть это поле, но в обучении V12.6 оно не представлено. |


991 из 1729 примеров создания имеют один набор полей: title + starts_at + duration_min. Одновременное присутствие редких полей не следует оценивать по отдельной частоте каждого из них.

Из 256 интервалов получается 65 различных пар «системный контекст + полный params». Это точная группировка по записанным полям, не автоматическая оценка смыслового разнообразия.

## Перестановки слов и независимые сценарии

Повторно подсчитаны 12 порядков интервала по coverage_index.json и существующей ручной разметке унаследованных строк: DEI, DIE, DSEF, EDI, EID, ESDF, IDE, IED, SDEF, SDFE, SEDF, SEFD — **по 20 строк**. Есть ещё одна унаследованная строка без явной даты, помеченная IE; она не входит в эти 240 строк.

Новая категория order_interval содержит 193 строки, но только 10 размеченных базовых семейств. Все 193 используют один системный контекст: 2028-02-28, 10:00, Europe/Samara. Её validation и контроль содержат по два базовых семейства, каждое в 12 порядках. Поэтому 24 контрольные строки не равны 24 независимым сценариям.

В новом coverage_index ни одно семейство не пересекает train, validation и контроль. Эта часть разделения выполнена последовательно. При расширении следует добавлять новые базовые сценарии и контексты, сохраняя сравнимое покрытие равноправных порядков; размножение одних и тех же сценариев не обеспечивает разнообразия.

## Разделение обучения и проверки

В 132 из 803 строк validation (16,44%) последовательность пользовательских сообщений дословно встречается в train. Это 102 различные формулировки. При этом полного совпадения prompt, включающего system и все предыдущие сообщения, нет: даты/время контекста различаются.

Поэтому это не следует называть 132 полными дубликатами. Эти строки проверяют, в частности, перенос на другой временной контекст, но не независимость формулировок. Для проверки новых формулировок необходим отдельный разрез оценки. Степень завышения метрик этим пересечением не измерена.

В train 2529 различных последовательностей пользовательских сообщений на 2938 строк; отдельная формулировка повторяется до 13 раз с разным контекстом. В семи контрольных файлах нет точных или проверенных нормализованных пользовательских последовательностей из train. Нормализация этой проверки: регистр, ё/е, повторные пробелы, крайние пробелы/точки/вопросительные/восклицательные знаки. Это не доказывает отсутствия всех близких перефразировок.

Метаданные train и validation часто имеют разные имена для одного навыка: например, manual_v8_search_full_query и manual_v8_eval_search_full_query. Ноль в таблице для буквального названия category не означает отсутствие навыка в другой выборке.

## Различия между обучением и Android

Все 3945 строк используют системное сообщение вида:

```text
Сегодня дата и время:2026-08-24 (понедельник) 14:30 Europe/Samara ответ JSON
```

Текущий SystemPromptProvider для календарного режима формирует:

```text
cегодня 2026-08-24 14:30 день недели понедельник ответ JSON
```

Второй пример показан для той же даты для сравнения форматов; фактически Android подставляет текущее локальное время. Начальная c в исходнике — латинская. Отличаются текст, положение дня недели и наличие названия часового пояса. Обнаруженное различие проверено по текущему исходнику; соответствие этого исходника установленному на телефоне APK не проверялось.


Путь исполнения: [ChatViewModel](C:/Users/007/AndroidStudioProjects/AIAssistent1/app/src/main/java/com/example/aiassistent1/presentation/viewmodel/ChatViewModel.kt:521) → [SendMessageUseCase](C:/Users/007/AndroidStudioProjects/AIAssistent1/app/src/main/java/com/example/aiassistent1/domain/usecase/SendMessageUseCase.kt:18) → [SystemPromptProvider](C:/Users/007/AndroidStudioProjects/AIAssistent1/app/src/main/java/com/example/aiassistent1/domain/provider/SystemPromptProvider.kt:8).


На одинаковом содержимом сообщений также проверен шаблон обрамления: официальный локальный токенизатор не добавляет перевод строки перед <|im_end|>, а LlamatikEngine.buildPrompt добавляет. Это подтверждённое текстовое различие, но не измеренная причина падения качества.


Источник: [buildPrompt](C:/Users/007/AndroidStudioProjects/AIAssistent1/app/src/main/java/com/example/aiassistent1/data/engine/LlamatikEngine.kt:153).


94 обучающие строки и 29 validation-строк содержат два сообщения user перед ответом. Например, train:21 объединяет создание пробежки и отдельное «На пятнадцать минут». Текущий ModelContextBuilder в календарном режиме передаёт только последнее пользовательское сообщение. Такие примеры следует отдельно пересмотреть на соответствие существующему режиму одного запроса; возвращать историю в Android без согласования нельзя.


Источник: [отбор сообщений](C:/Users/007/AndroidStudioProjects/AIAssistent1/app/src/main/java/com/example/aiassistent1/domain/context/ModelContextBuilder.kt:18).


## Что показывают сохранённые результаты модели

Это повторный анализ сохранённых ответов от 15 сентября, без новой генерации. Исходный запуск выполнен на NF4-базе и LoRA-адаптере. Он не измеряет потери от Q4_K_M, скорость телефона или фактическую работу установленного приложения.

Корректный JSON: 204/204. Правильный intent: 199/204. Точное совпадение intent + params: 123/204 (60,29%); при применении v12.57 столько же прошли структурный контракт. Сам контракт проходит 187/204. Семантика reply отдельно не проверялась.


| Набор | Точных ответов | Всего |
| --- | --- | --- |
| order_holdout | 22 | 43 |
| half_hour_holdout | 31 | 36 |
| interval_holdout | 7 | 12 |
| last_event_holdout | 2 | 6 |
| holdout | 16 | 36 |
| calendar_holdout_v12_2 | 5 | 8 |
| regression_holdout | 40 | 63 |


| Команда | Точных ответов по старым эталонам | Всего |
| --- | --- | --- |
| calendar_add | 98 | 155 |
| calendar_update | 13 | 25 |
| calendar_search | 8 | 14 |
| chat | 0 | 1 |
| calendar_delete | 3 | 5 |
| calendar_sum | 1 | 4 |


Для chat, sum и delete контроль особенно мал: 1, 4 и 5 случаев соответственно. По таким размерам нельзя надёжно оценить качество команды в реальном использовании. Кроме того, часть эталонов query требует описанной выше ревизии.

Подтверждённые числовые ошибки в сохранённых ответах:

- OIH01-ESDF: окончание «пол десятого» ожидается 21:30, модель выдала 22:30.
- OIH02-SEDF: интервал 23:55–00:05 должен перейти на следующий день, модель оставила окончание в дате начала.
- OPH03-DVSEF: «значение семь» превратилось в duration_min: 420, а value и ends_at потерялись.
- LEH01: ожидалось changes.time: 22:30, получено changes.starts_at: 10:30.

Эти наблюдения подтверждают необходимость проверки времени, границ суток и различения ролей чисел. Одно изменение долей intent их устранение не гарантирует.

## Проверки, выполненные в этом аудите

- Все 9 JSONL-файлов прочитаны; SHA-256 совпали с манифестом набора. Train и validation также совпали с манифестом реального обучения.
- Все 3945 строк проходят существующий normalize_record и проверку ответа как v12.57. Структурная корректность не обнаруживает продемонстрированное смысловое сокращение query.
- Точных дубликатов полного prompt внутри каждой выборки и между train и проверочными выборками не найдено. Не найдено разных intent/params у одного и того же полного prompt.
- Повторно выполнена локальная токенизация всех 3945 строк зафиксированным токенизатором Qwen3 с лимитом 256: ошибок нет, ответ assistant непустой, обрезки нет. Максимум train — 256 токенов, validation — 206. Веса модели не загружались.
- Проверены маскирование prompt в train_qlora.py и отличие обрамления сообщений Android от результата apply_chat_template.
- Сохранённый запуск обучения: одна эпоха, 184 шага, learning rate 1e-4, LoRA rank 16/alpha 32. Train loss 0.165778; eval loss 0.078346. По одному loss нельзя заключить, что команды выполняются правильно или что обязательно нужно больше эпох.
- Новое обучение, генерация GGUF, инференс, сборка Android и проверки на телефоне в этом аудите не запускались.

## Предлагаемый план после подтверждения

1. Создать новую версию набора в отдельной папке. Сохранить исходные V12.6, веса, исторические контрольные файлы и файлы правил. Завести журнал «файл/строка → дефект → исправление → ссылка на существующее правило».
2. Провести смысловую ревизию эталонов train/validation и контрольных ожиданий: полная цель query/title, время без части суток по действующим правилам, переход через полночь, пропущенные поля, value/ноль/очистка, источник и новая дата в update. Каждую корректировку привязать к существующему правилу; не изменять правила и не ослаблять валидатор ради прохождения данных.
3. Построить единую таблицу навыков поверх исторических category: команда, набор полей, форма даты/времени, порядок частей, граница суток, полнота запроса, роль числа. Считать одновременно строки и независимые базовые сценарии. Для равноправных порядков обеспечить сравнимое число разных сценариев; для всех 210 меток искусственного равенства не вводить. Квоты определить по этой таблице и наблюдаемым ошибкам, без обещания гарантированной точности от произвольного числа строк.
4. Вручную подготовить разнообразные примеры для пробелов: ends_at + value, ends_at + duration_min, неполные команды, целевой период и правки, полные названия, первые из нескольких событий. Менять даты, контекст текущего времени, названия, словесные/цифровые формы. Добавлять как присутствующие, так и отсутствующие значения; избегать заполнения неназванных полей.
5. Разделить данные по базовым сценариям до создания перестановок. Подготовить независимый контроль новых формулировок и отдельно контроль переноса дат. Известные разобранные ошибки оставить регрессионными примерами; не выдавать их за новый слепой контроль. Исторические ошибочные ожидания не перезаписывать: выпустить отдельную версию ожиданий с журналом оснований. Для ранее редких команд увеличить независимое контрольное покрытие.
6. Измерить текущую V12.6 Q4_K_M до переобучения: одинаковые случаи, одинаковые параметры генерации, отдельно обучающий формат prompt и текущий Android-формат. Это позволит проверить влияние формата на одной и той же модели. Выбор будущего формата данных/обрамления сделать по результатам и совместимости; изменение промпта или Android-кода не входит в текущий выполненный аудит.
7. После подготовки и проверки данных обучить эксперимент с контролируемым бюджетом и сравнить с исходной моделью: точный intent + params, отдельные поля, отсутствие выдуманных значений, корректность reply, устойчивость к перестановкам. Затем отдельно проверить Q4_K_M и установленное приложение. Улучшение принимать только вместе с проверкой регрессий search/update/delete/sum. Если дальнейшее разрешение ограничено подготовкой данных, обучение и Android-изменения вынести на отдельное согласование.

Это предложение работ, а не уже начатые изменения. До подтверждения выполняется только этот аудит и запись его результатов.

## Почему нет универсальной квоты «всем поровну»

Равное число примеров может быть полезно для контролируемого сравнения равноправных вариантов одного навыка. Оно не учитывает различие сложности навыков, качество ответов и реальную частоту запросов. В работах по смесям данных исследуют подбор пропорций и разнообразия, а не одну универсальную норму равенства: [Mixture-of-Skills, авторская публикация](https://arxiv.org/abs/2406.08811); [Google Research о Flan Collection](https://research.google/blog/the-flan-collection-advancing-open-source-methods-for-instruction-tuning/).

Это общее методическое основание. Конкретная рекомендация для проекта следует прежде всего из проверенных выше данных: равные 20 строк на порядок уже есть, а смысловые ошибки и ошибки извлечения остаются.

## Полная таблица меток category

Ниже буквальные метки из train, validation и семи контрольных файлов. Имена с eval могут обозначать тот же навык, что соответствующее имя без eval. Таблица показывает количество строк, не сложность категории и не число независимых смысловых сценариев.


| category | Train | Validation | Контроль |
| --- | --- | --- | --- |
| calendar_add_asr_variant | 1 | 0 | 0 |
| calendar_add_complete | 219 | 54 | 0 |
| calendar_add_default_today | 0 | 1 | 0 |
| calendar_add_relative_date | 0 | 1 | 0 |
| calendar_add_spoken_noon | 1 | 0 | 0 |
| calendar_add_spoken_noon_eval | 0 | 1 | 0 |
| calendar_add_weekday | 0 | 1 | 0 |
| calendar_delete | 50 | 13 | 0 |
| calendar_delete_last_created | 6 | 1 | 0 |
| calendar_delete_last_in_range | 6 | 1 | 0 |
| calendar_delete_with_period | 1 | 0 | 0 |
| calendar_search | 187 | 47 | 0 |
| calendar_search_day_after_tomorrow | 1 | 0 | 0 |
| calendar_search_explicit_date | 1 | 0 | 0 |
| calendar_search_name | 0 | 1 | 0 |
| calendar_search_next_week | 0 | 1 | 0 |
| calendar_search_previous_month_phrase | 21 | 9 | 0 |
| calendar_search_third_day | 1 | 0 | 0 |
| calendar_search_tomorrow_keyword | 1 | 0 | 0 |
| calendar_update_duration_by_query | 35 | 9 | 0 |
| calendar_update_implicit_last_created | 34 | 8 | 0 |
| calendar_update_last_created_spoken_noon | 1 | 0 | 0 |
| calendar_update_last_created_time | 34 | 8 | 0 |
| calendar_update_move_by_query | 35 | 9 | 0 |
| calendar_update_move_from_source_period | 36 | 9 | 0 |
| calendar_update_move_next_month | 0 | 1 | 0 |
| calendar_update_time_by_query | 35 | 10 | 0 |
| calendar_update_time_spoken_noon | 1 | 0 | 0 |
| calendar_update_time_spoken_noon_eval | 0 | 1 | 0 |
| calendar_update_title_by_query | 35 | 9 | 0 |
| holdout_calendar_add_asr | 0 | 0 | 1 |
| holdout_calendar_add_complete | 0 | 0 | 1 |
| holdout_calendar_add_default_tomorrow | 0 | 0 | 1 |
| holdout_calendar_add_four_days | 0 | 0 | 1 |
| holdout_calendar_add_leap_day | 0 | 0 | 1 |
| holdout_calendar_add_next_month | 0 | 0 | 1 |
| holdout_calendar_add_one_month | 0 | 0 | 1 |
| holdout_calendar_add_two_months | 0 | 0 | 1 |
| holdout_calendar_add_year_rollover | 0 | 0 | 1 |
| holdout_calendar_delete_colloquial | 0 | 0 | 1 |
| holdout_calendar_delete_last_created | 0 | 0 | 1 |
| holdout_calendar_delete_last_today | 0 | 0 | 1 |
| holdout_calendar_delete_with_period | 0 | 0 | 1 |
| holdout_calendar_search_day_after_tomorrow | 0 | 0 | 1 |
| holdout_calendar_search_four_days | 0 | 0 | 1 |
| holdout_calendar_search_month_ago | 0 | 0 | 1 |
| holdout_calendar_search_next_month | 0 | 0 | 1 |
| holdout_calendar_search_one_month | 0 | 0 | 1 |
| holdout_calendar_search_previous_month_phrase | 0 | 0 | 1 |
| holdout_calendar_search_this_week | 0 | 0 | 1 |
| holdout_calendar_search_three_days | 0 | 0 | 1 |
| holdout_calendar_search_today | 0 | 0 | 1 |
| holdout_calendar_search_tomorrow_keyword | 0 | 0 | 1 |
| holdout_calendar_search_two_days | 0 | 0 | 1 |
| holdout_calendar_search_two_months | 0 | 0 | 1 |
| holdout_calendar_search_year_rollover | 0 | 0 | 1 |
| holdout_calendar_update_duration_by_query | 0 | 0 | 1 |
| holdout_calendar_update_implicit_last_created | 0 | 0 | 1 |
| holdout_calendar_update_last_created_duration | 0 | 0 | 1 |
| holdout_calendar_update_move_by_query | 0 | 0 | 1 |
| holdout_calendar_update_multiple_changes | 0 | 0 | 1 |
| holdout_calendar_update_next_month | 0 | 0 | 1 |
| holdout_calendar_update_old_date_new_date | 0 | 0 | 1 |
| holdout_calendar_update_previous_month_source | 0 | 0 | 1 |
| holdout_calendar_update_rename | 0 | 0 | 1 |
| holdout_calendar_update_source_period | 0 | 0 | 1 |
| holdout_calendar_update_time_by_query | 0 | 0 | 1 |
| holdout_calendar_update_time_colloquial | 0 | 0 | 1 |
| holdout_ordinary_chat | 0 | 0 | 1 |
| holdout_v5_add_complete_value | 0 | 0 | 1 |
| holdout_v5_add_explicit_today_wins | 0 | 0 | 1 |
| holdout_v5_add_implicit_earlier_tomorrow | 0 | 0 | 1 |
| holdout_v5_add_implicit_equal_tomorrow | 0 | 0 | 1 |
| holdout_v5_add_implicit_today_later | 0 | 0 | 1 |
| holdout_v5_add_title_only | 0 | 0 | 1 |
| holdout_v5_add_value_only | 0 | 0 | 1 |
| holdout_v5_add_without_duration | 0 | 0 | 1 |
| holdout_v5_add_without_time | 0 | 0 | 1 |
| holdout_v5_add_without_title | 0 | 0 | 1 |
| holdout_v5_delete_without_target | 0 | 0 | 1 |
| holdout_v5_sum_in_one_month | 0 | 0 | 1 |
| holdout_v5_sum_previous_week | 0 | 0 | 1 |
| holdout_v5_sum_today | 0 | 0 | 1 |
| holdout_v5_sum_without_period | 0 | 0 | 1 |
| holdout_v5_update_clear_value | 0 | 0 | 1 |
| holdout_v5_update_value | 0 | 0 | 1 |
| holdout_v5_update_without_changes | 0 | 0 | 1 |
| last_event_find_and_update | 10 | 2 | 2 |
| last_event_last_created | 10 | 2 | 2 |
| last_event_named_event | 10 | 2 | 2 |
| manual_v10_calendar_offset_expansion | 12 | 0 | 0 |
| manual_v10_capabilities | 8 | 0 | 0 |
| manual_v10_capabilities_expansion | 6 | 0 | 0 |
| manual_v10_current_datetime | 10 | 0 | 0 |
| manual_v10_current_datetime_expansion | 10 | 0 | 0 |
| manual_v10_eval_calendar_offset_expansion | 0 | 4 | 0 |
| manual_v10_eval_capabilities | 0 | 2 | 0 |
| manual_v10_eval_capabilities_expansion | 0 | 2 | 0 |
| manual_v10_eval_current_datetime | 0 | 3 | 0 |
| manual_v10_eval_current_datetime_expansion | 0 | 4 | 0 |
| manual_v10_eval_identity | 0 | 2 | 0 |
| manual_v10_eval_identity_expansion | 0 | 2 | 0 |
| manual_v10_eval_long_offset | 0 | 2 | 0 |
| manual_v10_eval_named_date_expansion | 0 | 5 | 0 |
| manual_v10_eval_named_date_weekday | 0 | 5 | 0 |
| manual_v10_eval_relative_clock | 0 | 5 | 0 |
| manual_v10_eval_relative_clock_expansion | 0 | 5 | 0 |
| manual_v10_eval_relative_day | 0 | 5 | 0 |
| manual_v10_eval_relative_day_expansion | 0 | 4 | 0 |
| manual_v10_eval_week_structure | 0 | 2 | 0 |
| manual_v10_identity | 8 | 0 | 0 |
| manual_v10_identity_expansion | 6 | 0 | 0 |
| manual_v10_long_offset | 8 | 0 | 0 |
| manual_v10_named_date_expansion | 16 | 0 | 0 |
| manual_v10_named_date_weekday | 12 | 0 | 0 |
| manual_v10_relative_clock | 12 | 0 | 0 |
| manual_v10_relative_clock_expansion | 14 | 0 | 0 |
| manual_v10_relative_day | 12 | 0 | 0 |
| manual_v10_relative_day_expansion | 12 | 0 | 0 |
| manual_v10_week_structure | 8 | 0 | 0 |
| manual_v11_eval_h004_leap_day | 0 | 5 | 0 |
| manual_v11_eval_h008_implicit_date | 0 | 5 | 0 |
| manual_v11_eval_h011_duration_twenty | 0 | 5 | 0 |
| manual_v11_h004_leap_day | 15 | 0 | 0 |
| manual_v11_h008_implicit_date | 15 | 0 | 0 |
| manual_v11_h011_duration_twenty | 15 | 0 | 0 |
| manual_v12_duration_aliases | 27 | 0 | 0 |
| manual_v12_duration_composite | 40 | 0 | 0 |
| manual_v12_duration_hour_minus | 29 | 0 | 0 |
| manual_v12_duration_minute_words | 60 | 0 | 0 |
| manual_v12_eval_duration_aliases | 0 | 9 | 0 |
| manual_v12_eval_duration_composite | 0 | 10 | 0 |
| manual_v12_eval_duration_hour_minus | 0 | 10 | 0 |
| manual_v12_eval_duration_minute_words | 0 | 15 | 0 |
| manual_v12_eval_omit_date_only | 0 | 1 | 0 |
| manual_v12_eval_omit_date_time | 0 | 1 | 0 |
| manual_v12_eval_omit_time_only | 0 | 1 | 0 |
| manual_v12_eval_omit_title_date | 0 | 1 | 0 |
| manual_v12_eval_omit_title_date_time | 0 | 1 | 0 |
| manual_v12_eval_omit_title_only | 0 | 1 | 0 |
| manual_v12_eval_omit_title_time | 0 | 2 | 0 |
| manual_v12_eval_value_clear | 0 | 1 | 0 |
| manual_v12_eval_value_update | 0 | 2 | 0 |
| manual_v12_omit_date_only | 5 | 0 | 0 |
| manual_v12_omit_date_time | 4 | 0 | 0 |
| manual_v12_omit_time_only | 5 | 0 | 0 |
| manual_v12_omit_title_date | 5 | 0 | 0 |
| manual_v12_omit_title_date_time | 4 | 0 | 0 |
| manual_v12_omit_title_only | 5 | 0 | 0 |
| manual_v12_omit_title_time | 4 | 0 | 0 |
| manual_v12_value_add | 9 | 0 | 0 |
| manual_v12_value_clear | 1 | 0 | 0 |
| manual_v12_value_update | 2 | 0 | 0 |
| manual_v5_add_ambiguous_daypart | 1 | 0 | 0 |
| manual_v5_add_complete_value | 1 | 0 | 0 |
| manual_v5_add_duration_only | 1 | 0 | 0 |
| manual_v5_add_explicit_today_wins | 1 | 0 | 0 |
| manual_v5_add_implicit_equal_tomorrow | 1 | 0 | 0 |
| manual_v5_add_implicit_today_later | 1 | 0 | 0 |
| manual_v5_add_implicit_tomorrow | 1 | 0 | 0 |
| manual_v5_add_negative_value_only | 1 | 0 | 0 |
| manual_v5_add_title_only | 2 | 0 | 0 |
| manual_v5_add_title_value | 1 | 0 | 0 |
| manual_v5_add_without_time | 1 | 0 | 0 |
| manual_v5_add_without_title | 1 | 0 | 0 |
| manual_v5_add_zero_value | 1 | 0 | 0 |
| manual_v5_delete_without_target | 1 | 0 | 0 |
| manual_v5_eval_add_complete_value | 0 | 1 | 0 |
| manual_v5_eval_add_implicit_today | 0 | 1 | 0 |
| manual_v5_eval_add_implicit_year_rollover | 0 | 1 | 0 |
| manual_v5_eval_add_title_only | 0 | 1 | 0 |
| manual_v5_eval_add_without_time | 0 | 1 | 0 |
| manual_v5_eval_delete_without_target | 0 | 1 | 0 |
| manual_v5_eval_sum_current_month | 0 | 1 | 0 |
| manual_v5_eval_sum_current_year | 0 | 1 | 0 |
| manual_v5_eval_sum_day_before_yesterday | 0 | 1 | 0 |
| manual_v5_eval_sum_in_four_months | 0 | 1 | 0 |
| manual_v5_eval_sum_next_month | 0 | 1 | 0 |
| manual_v5_eval_sum_next_week | 0 | 1 | 0 |
| manual_v5_eval_sum_without_period | 0 | 1 | 0 |
| manual_v5_eval_update_clear_value | 0 | 1 | 0 |
| manual_v5_eval_update_without_changes | 0 | 1 | 0 |
| manual_v5_search_without_fields | 1 | 0 | 0 |
| manual_v5_search_without_period | 1 | 0 | 0 |
| manual_v5_sum_current_month_query | 1 | 0 | 0 |
| manual_v5_sum_current_year | 1 | 0 | 0 |
| manual_v5_sum_in_half_year | 1 | 0 | 0 |
| manual_v5_sum_in_one_month | 1 | 0 | 0 |
| manual_v5_sum_in_quarter | 1 | 0 | 0 |
| manual_v5_sum_in_two_months | 1 | 0 | 0 |
| manual_v5_sum_next_month | 1 | 0 | 0 |
| manual_v5_sum_next_year | 1 | 0 | 0 |
| manual_v5_sum_previous_month | 1 | 0 | 0 |
| manual_v5_sum_previous_week | 1 | 0 | 0 |
| manual_v5_sum_previous_year | 1 | 0 | 0 |
| manual_v5_sum_today | 1 | 0 | 0 |
| manual_v5_sum_without_period | 1 | 0 | 0 |
| manual_v5_sum_yesterday_query | 1 | 0 | 0 |
| manual_v5_update_clear_value | 1 | 0 | 0 |
| manual_v5_update_last_value | 1 | 0 | 0 |
| manual_v5_update_value | 1 | 0 | 0 |
| manual_v5_update_without_changes | 1 | 0 | 0 |
| manual_v6_action_reply_schema | 4 | 0 | 0 |
| manual_v6_add_date_only_title | 8 | 0 | 0 |
| manual_v6_add_daypart_unknown_time | 8 | 0 | 0 |
| manual_v6_add_daypartless_literal | 8 | 0 | 0 |
| manual_v6_add_duration_only | 8 | 0 | 0 |
| manual_v6_add_explicit_today | 8 | 0 | 0 |
| manual_v6_add_implicit_earlier | 10 | 0 | 0 |
| manual_v6_add_implicit_equal | 10 | 0 | 0 |
| manual_v6_add_implicit_later | 12 | 0 | 0 |
| manual_v6_add_negative_zero_value | 6 | 0 | 0 |
| manual_v6_add_no_invention | 8 | 0 | 0 |
| manual_v6_add_time_only | 8 | 0 | 0 |
| manual_v6_add_title_only | 8 | 0 | 0 |
| manual_v6_add_value | 8 | 0 | 0 |
| manual_v6_chat_how_to | 10 | 0 | 0 |
| manual_v6_complete_reply_schema | 4 | 0 | 0 |
| manual_v6_delete_empty_target | 6 | 0 | 0 |
| manual_v6_delete_last_in_range | 6 | 0 | 0 |
| manual_v6_delete_named_target | 6 | 0 | 0 |
| manual_v6_duration_long_hours | 8 | 0 | 0 |
| manual_v6_duration_minutes | 8 | 0 | 0 |
| manual_v6_duration_mixed | 8 | 0 | 0 |
| manual_v6_eval_add_implicit_date | 0 | 8 | 0 |
| manual_v6_eval_add_partial_fields | 0 | 8 | 0 |
| manual_v6_eval_chat_vs_command | 0 | 4 | 0 |
| manual_v6_eval_daypartless | 0 | 4 | 0 |
| manual_v6_eval_delete_target | 0 | 4 | 0 |
| manual_v6_eval_duration | 0 | 8 | 0 |
| manual_v6_eval_no_invention | 0 | 4 | 0 |
| manual_v6_eval_search_query_all | 0 | 4 | 0 |
| manual_v6_eval_sum_days_weeks | 0 | 6 | 0 |
| manual_v6_eval_sum_months_offsets | 0 | 6 | 0 |
| manual_v6_eval_sum_year_without_period | 0 | 4 | 0 |
| manual_v6_eval_update_target | 0 | 4 | 0 |
| manual_v6_eval_value_add | 0 | 4 | 0 |
| manual_v6_eval_value_update_clear | 0 | 4 | 0 |
| manual_v6_executable_command | 10 | 0 | 0 |
| manual_v6_fractional_value_omitted | 6 | 0 | 0 |
| manual_v6_partial_reply_schema | 4 | 0 | 0 |
| manual_v6_search_all | 6 | 0 | 0 |
| manual_v6_search_query | 6 | 0 | 0 |
| manual_v6_sum_day | 8 | 0 | 0 |
| manual_v6_sum_empty_period | 4 | 0 | 0 |
| manual_v6_sum_month | 8 | 0 | 0 |
| manual_v6_sum_offset | 8 | 0 | 0 |
| manual_v6_sum_week | 8 | 0 | 0 |
| manual_v6_sum_without_period | 8 | 0 | 0 |
| manual_v6_sum_year | 8 | 0 | 0 |
| manual_v6_update_clear_value | 8 | 0 | 0 |
| manual_v6_update_empty_changes | 6 | 0 | 0 |
| manual_v6_update_named_target | 6 | 0 | 0 |
| manual_v6_update_source_range | 6 | 0 | 0 |
| manual_v6_update_value | 8 | 0 | 0 |
| manual_v7_calendar_leap_boundary | 16 | 0 | 0 |
| manual_v7_clock_24h_hour | 24 | 0 | 0 |
| manual_v7_clock_24h_minute | 24 | 0 | 0 |
| manual_v7_clock_daypart_equivalent | 24 | 0 | 0 |
| manual_v7_clock_explicit_date_priority | 8 | 0 | 0 |
| manual_v7_clock_implicit_date | 12 | 0 | 0 |
| manual_v7_duration_day_units | 8 | 0 | 0 |
| manual_v7_eval_calendar_leap_boundary | 0 | 8 | 0 |
| manual_v7_eval_clock_24h | 0 | 24 | 0 |
| manual_v7_eval_clock_semantics | 0 | 8 | 0 |
| manual_v7_eval_day_units | 0 | 8 | 0 |
| manual_v7_offset_day_units | 8 | 0 | 0 |
| manual_v8_delete_full_query | 4 | 0 | 0 |
| manual_v8_eval_delete_full_query | 0 | 2 | 0 |
| manual_v8_eval_search_four_days_all | 0 | 1 | 0 |
| manual_v8_eval_search_four_days_named | 0 | 3 | 0 |
| manual_v8_eval_search_full_query | 0 | 2 | 0 |
| manual_v8_eval_sum_full_query | 0 | 2 | 0 |
| manual_v8_eval_title_semantic_full | 0 | 2 | 0 |
| manual_v8_eval_update_full_query | 0 | 2 | 0 |
| manual_v8_search_four_days_all | 1 | 0 | 0 |
| manual_v8_search_four_days_named | 6 | 0 | 0 |
| manual_v8_search_full_query | 6 | 0 | 0 |
| manual_v8_sum_full_query | 4 | 0 | 0 |
| manual_v8_title_semantic_full | 6 | 0 | 0 |
| manual_v8_update_full_query | 5 | 0 | 0 |
| manual_v9_century_leap | 2 | 0 | 0 |
| manual_v9_clock_duration_offset | 4 | 0 | 0 |
| manual_v9_eval_month | 0 | 6 | 0 |
| manual_v9_eval_month_offset | 0 | 4 | 0 |
| manual_v9_eval_quarter_half_year | 0 | 3 | 0 |
| manual_v9_eval_season | 0 | 4 | 0 |
| manual_v9_eval_unit_and_role | 0 | 4 | 0 |
| manual_v9_eval_weekday | 0 | 4 | 0 |
| manual_v9_eval_year_leap | 0 | 3 | 0 |
| manual_v9_half_year_range | 2 | 0 | 0 |
| manual_v9_month_length | 12 | 0 | 0 |
| manual_v9_month_offset_contrast | 8 | 0 | 0 |
| manual_v9_month_range | 12 | 0 | 0 |
| manual_v9_next_weekday_range | 7 | 0 | 0 |
| manual_v9_period_unit_fact | 2 | 0 | 0 |
| manual_v9_quarter_range | 4 | 0 | 0 |
| manual_v9_season_definition | 4 | 0 | 0 |
| manual_v9_season_range | 8 | 0 | 0 |
| manual_v9_unit_fact | 4 | 0 | 0 |
| manual_v9_weekday_order | 7 | 0 | 0 |
| manual_v9_year_fact | 4 | 0 | 0 |
| manual_v9_year_offset_contrast | 2 | 0 | 0 |
| manual_v9_year_range | 2 | 0 | 0 |
| order_duration_value | 24 | 6 | 6 |
| order_first_event | 20 | 2 | 2 |
| order_interval | 193 | 24 | 24 |
| order_partial | 26 | 3 | 5 |
| order_single_event_control | 8 | 2 | 2 |
| order_update_order | 20 | 4 | 4 |
| ordinary_chat | 10 | 4 | 0 |
| reinforcement_add_complete_asr | 12 | 3 | 0 |
| reinforcement_delete_full_query | 12 | 3 | 0 |
| reinforcement_delete_last_created | 12 | 3 | 0 |
| reinforcement_delete_last_in_range | 12 | 3 | 0 |
| reinforcement_search_full_query_day | 12 | 3 | 0 |
| reinforcement_search_previous_month_full_query | 12 | 3 | 0 |
| reinforcement_search_two_months_full_query | 12 | 3 | 0 |
| reinforcement_search_week_full_query | 12 | 3 | 0 |
| reinforcement_update_duration_not_add | 12 | 3 | 0 |
| reinforcement_update_source_range | 12 | 3 | 0 |
| reinforcement_update_time_full_query | 12 | 3 | 0 |
| v12_1_literal_title | 1 | 1 | 1 |
| v12_1_no_title | 1 | 1 | 1 |
| v12_1_short_context | 1 | 1 | 1 |
| v12_1_title_duration | 1 | 0 | 0 |
| v12_1_title_explicit_date | 0 | 0 | 1 |
| v12_1_title_relative | 2 | 1 | 0 |
| v12_1_title_time | 1 | 0 | 0 |
| v12_1_title_uppercase | 1 | 1 | 1 |
| v12_1_title_value | 1 | 0 | 0 |
| v12_1_title_weekday | 1 | 0 | 0 |
| v12_1_title_word_order | 2 | 1 | 1 |
| v12_2_common_calendar_add | 12 | 4 | 4 |
| v12_2_common_chat | 4 | 0 | 0 |
| v12_2_leap_calendar_add | 12 | 4 | 4 |
| v12_2_leap_chat | 4 | 0 | 0 |
| v12_3_word_order_day_time_title | 10 | 6 | 6 |
| v12_3_word_order_day_title_time | 10 | 6 | 6 |
| v12_3_word_order_time_day_title | 10 | 6 | 6 |
| v12_3_word_order_time_title_day | 10 | 6 | 6 |
| v12_3_word_order_title_day_time | 10 | 6 | 6 |
| v12_3_word_order_title_time_day | 10 | 6 | 6 |
| v12_51_on_hour_compact | 24 | 0 | 0 |
| v12_51_on_hour_expanded | 24 | 0 | 0 |
| v12_51_on_hour_mixed | 0 | 24 | 0 |
| v12_52_minutes_of_hour | 132 | 36 | 0 |
| v12_53_compact_clock | 264 | 72 | 0 |
| v12_53_midnight | 1 | 1 | 0 |
| v12_55_half_hour_clock | 144 | 24 | 24 |
| v12_55_half_hour_duration | 0 | 0 | 3 |
| v12_55_half_hour_offset | 0 | 0 | 3 |
| v12_56_default_half | 24 | 6 | 12 |
| v12_56_event_interval | 48 | 6 | 6 |
| v12_5_clock_pol | 2 | 2 | 0 |
| v12_5_clock_polovina | 2 | 2 | 0 |
| v12_5_clock_quarter | 2 | 2 | 0 |


Итого: 2938 train, 803 validation, 204 контрольных строк.
