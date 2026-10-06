# Удаление GGUF, 6 октября 2026 года

Пользователь подтвердил точный список из 34 GGUF в C:\Users\007, включая 19 служебных словарей llama.cpp. Другие диски и телефон в область операции не входят.

Состояние: **COMPLETE**. Объём подтверждённых целей: **59 319 966 040 байт / 55,2460 ГиБ**.

## Выполненные действия

Удаление выполняется только по фиксированному списку абсолютных путей через Remove-Item -LiteralPath, без рекурсивного удаления каталогов. Перед удалением проверяются границы профиля, отсутствие перенаправлений, размер и дата изменения. SHA-256 целей записан до удаления.
Такой порядок ограничивает операцию подтверждёнными бинарными артефактами и позволяет отдельно проверить сохранность V12.66 и материалов обучения.

## Удаляемые файлы

| Путь | Байты | Состояние |
| --- | ---: | --- |
| C:\Users\007\ai-asisntnt\ai-models\Phi-3.5-mini-instruct-Q4_K_M.gguf | 2393232672 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_sft_qwen3_v12_6_gguf_20260914\calendar-assistant-v12.6-Q4_K_M.gguf | 2497278816 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_sft_qwen3_v12_61_gguf_20260928\calendar-assistant-v12.61-Q4_K_M.gguf | 2497278816 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_sft_qwen3_v12_62_gguf_20260929\calendar-assistant-v12.62-Q4_K_M.gguf | 2497278816 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_sft_qwen3_v12_63_gguf_20261001\calendar-assistant-v12.63-Q4_K_M.gguf | 2497278816 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_sft_v12_6_q4_work_20260914\calendar-assistant-v12.6-F16.gguf | 8051283296 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_sft_v12_61_q4_work_20260928\calendar-assistant-v12.61-F16.gguf | 8051283296 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_sft_v12_62_q4_work_20260929\calendar-assistant-v12.62-F16.gguf | 8051283296 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_sft_v12_63_q4_work_20261001\calendar-assistant-v12.63-F16.gguf | 8051283296 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_small_models_20261003_v2\qwen3_1_7b\build\calendar-assistant-v12.63-Qwen3-1.7B-F16.gguf | 3447345024 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_small_models_20261003_v2\qwen3_1_7b\release\calendar-assistant-v12.63-Qwen3-1.7B-Q4_K_M.gguf | 1107404672 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_small_models_20261003_v2\qwen35_2b\build_text_only\calendar-assistant-v12.63-Qwen3.5-2B-F16.gguf | 3775700992 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_small_models_20261003_v2\qwen35_2b\build\calendar-assistant-v12.63-Qwen3.5-2B-F16.gguf | 3775701056 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_small_models_20261003_v2\qwen35_2b\release_failed_mtp\calendar-assistant-v12.63-Qwen3.5-2B-Q4_K_M.gguf | 1274388544 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_small_models_20261003_v2\qwen35_2b\release\calendar-assistant-v12.63-Qwen3.5-2B-Q4_K_M.gguf | 1274388480 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-aquila.gguf | 4825676 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-baichuan.gguf | 1340998 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-bert-bge.gguf | 627549 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-command-r.gguf | 10874545 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-deepseek-coder.gguf | 1156067 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-deepseek-llm.gguf | 3970167 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-falcon.gguf | 2287728 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-gemma-4.gguf | 15776467 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-gpt-2.gguf | 1766807 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-gpt-neox.gguf | 1771431 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-llama-bpe.gguf | 7818140 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-llama-spm.gguf | 723869 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-mpt.gguf | 1771393 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-nomic-bert-moe.gguf | 6821877 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-phi-3.gguf | 726019 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-qwen2.gguf | 5928681 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-qwen35.gguf | 5928682 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-refact.gguf | 1720710 | DELETED |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\llama.cpp-v0.3.0\models\ggml-vocab-starcoder.gguf | 1719346 | DELETED |

## Сохранённые V12.66

| Путь | Байты | SHA-256 |
| --- | ---: | --- |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_v12_66_qwen3_06b\qwen3_0_6b\build\calendar-assistant-v12.66-Qwen3-0.6B-F16.gguf | 1198178144 | f915bc51c6ee4ddc5673cb6217497fcf8caac21a996a42ed8d9aae07646482b9 |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_v12_66_qwen3_06b\qwen3_0_6b\release\calendar-assistant-v12.66-Qwen3-0.6B-Q4_K_M.gguf | 396700512 | 275179e2b0de7eee8c233b119bec9df368b501df948e1f6bb498e75779371bdd |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_v12_66_qwen35\qwen35_2b\build\calendar-assistant-v12.66-Qwen3.5-2B-F16.gguf | 3775700992 | b5290d7164ee65b09068b4cdff135e8cc3528cb18463f018c9d98f75a027d910 |
| C:\Users\007\AndroidStudioProjects\AIAssistent1\build\calendar_v12_66_qwen35\qwen35_2b\release\calendar-assistant-v12.66-Qwen3.5-2B-Q4_K_M.gguf | 1274388480 | 70d9525a18b9495ec2ed7f9ea66036bd20caaab6c05e30cd871af40f4c51ed50 |

## Материалы обучения

Датасеты, Safetensors, адаптеры, контрольные точки, журналы, отчёты, манифесты, исходный код, правила и файлы с расширениями .gguf.inp/.gguf.out не являются целями удаления.
Снимок сохраняемых материалов: C:\Users\007\AndroidStudioProjects\AIAssistent1\build\gguf_cleanup_20261006_preserved.json. В нём 14493 файлов, SHA-256 подготовлен для 11976 файлов.
Для крупных сохраняемых файлов проверяются наличие, размер и время изменения; их полное содержимое повторно не читается. Для всех четырёх сохраняемых GGUF сравнивается полный SHA-256.
Каталог параллельного запуска build/calendar_v12_66_qwen3_06b, его документация и скрипты проверяются на наличие файлов. Изменения размеров и контрольных сумм в этих путях отдельно учитываются как допустимые обновления параллельного запуска; из удаления они полностью исключены.

## Служебные словари

В подтверждённый список входят 19 GGUF-словарей llama.cpp общим размером 77 556 152 байта. Их удаление убирает входные данные соответствующих тестов токенизатора и грамматики; перед последующим использованием этих тестов словари потребуется восстановить.

## Проверка результата

Проверено отсутствие 34 целей; сохранённых GGUF с совпавшим SHA-256: 4; проверенных сохраняемых файлов: 14493; сравнений SHA-256: 11976; проверенных каталогов: 962; допустимых изменённых файлов параллельного запуска: 0; ошибок: 0.
Свободно на C: до операции 119385387008 байт, после операции 178701901824 байт. Разница включает записи других процессов и служебные отчёты, поэтому не является точным измерением физически освобождённых блоков GGUF.

Полные пути, даты и контрольные суммы находятся в calendar_gguf_cleanup_2026_10_06.json. Подтверждённый план calendar_gguf_cleanup_2026_10_06_plan.json сохранён без изменений.

## Независимый поиск оставшихся GGUF

После удаления выполнен отдельный поиск `rg --files --hidden --no-ignore --iglob '*.gguf' C:\Users\007`. В доступной части профиля найдены ровно четыре GGUF: сохранённые F16 и Q4_K_M версий V12.66 для Qwen3-0.6B и Qwen3.5-2B. Дополнительных доступных GGUF не найдено. Обход не переходил по ссылкам каталогов.

Поиск сообщил отказ в доступе к `C:\Users\007\AppData\Local\ElevatedDiagnostics`; содержимое этого каталога не проверено. Результат поиска поэтому относится к доступной части профиля. Отсутствие всех 34 согласованных целей проверено отдельно по точным путям.

## Временная блокировка журнала

После удаления 15 модельных файлов Windows сообщил, что JSON-журнал занят другим процессом. Повторная проверка установила совпадение журнала и фактического состояния: отсутствовали ровно эти 15 файлов. Запись журнала в служебном скрипте изменена на замену через временный файл с ограниченными повторами при ошибке ввода-вывода. Затем по тому же списку удалены оставшиеся 19 словарей и выполнена итоговая проверка без ошибок. Историческое сообщение о блокировке сохранено в JSON-журнале; итоговый статус операции — COMPLETE.

## Объём профиля после очистки и следующий этап

Обход метаданных 6 октября 2026 года, 12:10:42–12:11:03 UTC: **699 655 доступных обычных файлов, 434 470 731 627 байт / 404,63 ГиБ**. Свободно на C: 178 692 620 288 байт / 166,42 ГиБ; на D: 371 415 560 192 байта / 345,91 ГиБ. D: находится на физическом диске TOSHIBA MQ01ABF050, 500 107 862 016 байт.

Это логические размеры файлов при живом обходе, а не объём занятых блоков NTFS и не согласованный VSS-снимок. Пропущены 115 объектов reparse point и недоступный каталог ElevatedDiagnostics. Машинный результат: `build/profile_backup_size_20261006.json`; служебный счётчик: `build/profile_backup_size_20261006.ps1`. Данные профиля при подсчёте не изменялись.

Обычная несжатая копия доступных файлов превышает свободное место D: на **63 055 171 435 байт / 58,72 ГиБ**. Возможность уместить сжатый резерв ещё не проверена. Метаданные, предыдущие версии и запас свободного места увеличивают требования к месту.

Предлагаемый следующий план: выбрать формат хранения, измерить сжатие на данных профиля, затем создать первую полную копию при достаточном месте, проверить восстановление и настроить автоматическое обновление. Для архивного варианта предполагаемый новый каталог — `D:\Backups\007-restic`; его сейчас нет. Предлагаемый минимальный запас на D: — 10 ГиБ; это параметр плана, а не уже включённая настройка. Для такого запаса данные и служебная информация первой копии должны уместиться в 335,91 ГиБ. Предлагаемая периодичность обновления — один час, с сохранением предыдущих версий.

[Restic](https://restic.readthedocs.io/en/stable/040_backup.html) поддерживает сжатые снимки, повторное резервирование с дедупликацией, проверку репозитория и чтение занятых файлов Windows через VSS. Для нового репозитория также потребуется выбрать способ хранения пароля и обеспечить его наличие при восстановлении; [официальная документация](https://restic.readthedocs.io/en/stable/030_preparing_a_new_repo.html) описывает работу с ключами. Эти настройки ещё не применялись. Вариант с обычной папкой и прозрачным NTFS-сжатием также требует проверки фактической экономии места и отдельного плана сохранения предыдущих версий.

Текущий сеанс Windows не имеет прав администратора. Доступ к занятым или защищённым файлам, VSS и SMART потребуется проверить при подготовке резервирования. Копирование, сжатие данных профиля и автоматическая синхронизация пока не выполнялись; на D: файлы этой операцией не создавались.
