# Очистка старых GGUF — 29 сентября 2026 года

Пользователь подтвердил удаление ровно 19 старых GGUF в корневой папке build с сохранением V12.6 включительно и всех более новых выпусков. Изменяется только локальное наличие указанных бинарных артефактов; история обучения остаётся доступной.

Состояние операции: **COMPLETE**. Подготовлено: 2026-09-28T21:29:06.3999719Z.
Общий размер согласованных файлов: **84149241760 байт; 84,15 ГБ; 78,37 ГиБ**.

## Область и обоснование

- Удаляются GGUF выпусков V12, V12.3, V12.53, V12.54, V12.55, V12.56 и V12.57: они предшествуют V12.6 по истории выпусков этого проекта. Сравнение версий как десятичных чисел или SemVer здесь не применяется.
- Сохранены оба GGUF V12.6: Q4_K_M и промежуточный F16. Любые материалы V12.61 и последующих выпусков исключены из удаления.
- Сохранены 19 GGUF-словарей llama.cpp: это служебные файлы инструментов, а не старые выпуски обученной модели.
- Все датасеты, ручные реестры, конфигурации, журналы, метрики, отчёты, манифесты, контрольные точки, адаптеры, safetensors и скрипты сохраняются. Старые отчёты и манифесты описывают историческую сборку; упоминание удалённого GGUF в них после очистки ожидаемо.
- Исходный текст калибровки V12 imatrix_calibration_train_v12.txt и её параметры сохранены; удаляется только согласованный бинарный imatrix-train-v12.gguf.
- Удаление выполняется через Remove-Item -LiteralPath только по фиксированному списку, без рекурсивного удаления каталогов. Проверяются границы build, отсутствие перенаправлений, размеры, даты и контрольные суммы. Android-код и файлы правил не меняются.

## Реестр GGUF

Контрольные суммы вычислены непосредственно перед очисткой и сопоставлены с сохранёнными манифестами сборок. Пути ниже относительны корню проекта.

| Путь | Байты | SHA-256 | Состояние |
| --- | ---: | --- | --- |
| build/calendar_sft_qwen3_v12_gguf_20260907/calendar-assistant-v12-F16.gguf | 8051283296 | 505ec1adf18c2261655b5df991bbb2f490bfb2b31745ab6688775a5bfe1de9e1 | DELETED |
| build/calendar_sft_qwen3_v12_gguf_20260907/calendar-assistant-v12-IQ3_XXS.gguf | 1670186656 | 22ae09f6721be5b13b506ff7e5400dbeec95ae47380d7e193c283587c5cf3e5d | DELETED |
| build/calendar_sft_qwen3_v12_gguf_20260907/calendar-assistant-v12-Q3_K_M.gguf | 2075616096 | a8e6cd87f737e8d8cf9511bb2883b9a41a4d8e62f30df7e13553aa5f1baacbc3 | DELETED |
| build/calendar_sft_qwen3_v12_gguf_20260907/calendar-assistant-v12-Q3_K_S.gguf | 1886995296 | 31e850db453ed641c72286d21e5780a53ff8e71a014973f43f870e3923246fe5 | DELETED |
| build/calendar_sft_qwen3_v12_gguf_20260907/calendar-assistant-v12-Q4_K_M.gguf | 2497278816 | 1cb756ebc66a60b692e1985601108cee3a1294aa1951c783fe41d9ea63bd67cf | DELETED |
| build/calendar_sft_qwen3_v12_gguf_20260907/calendar-assistant-v12-Q5_K_M.gguf | 2889511776 | ebf75e55c1d85b687fd50479f0154f6551c5ab03a9f6fd2047eb9b42f00dbc0b | DELETED |
| build/calendar_sft_qwen3_v12_gguf_20260907/calendar-assistant-v12-Q8_0.gguf | 4280403296 | d0e8db0d67c752849f2aad4fb4f44ee5532fb2bd47e0d0fe664e7042b3bb73a6 | DELETED |
| build/calendar_sft_qwen3_v12_gguf_20260907/imatrix-train-v12.gguf | 3872672 | eae6d2299cc499aa2caed0e706b98c172f4d30159ab65d5d6fcbc7e6177726ac | DELETED |
| build/calendar_sft_v12_3_q4_work_20260909/calendar-assistant-v12.3-F16.gguf | 8051283296 | a46999be36009dd39b728d6a62d9c76870073f454752d48752eb7a40898e10fb | DELETED |
| build/calendar_sft_qwen3_v12_53_gguf_20260910/calendar-assistant-v12.53-Q4_K_M.gguf | 2497278816 | 191c667b385e502933ccf45de9b9517452f11af18830dc1233e9612e6fd18061 | DELETED |
| build/calendar_sft_v12_53_q4_work_20260910/calendar-assistant-v12.53-F16.gguf | 8051283296 | 5f76102e77c8408f9dbf4893872815a18805b71041226952518af5c099de724c | DELETED |
| build/calendar_sft_qwen3_v12_54_gguf_20260911/calendar-assistant-v12.54-Q4_K_M.gguf | 2497278816 | 53a0218340d601cf627329b0bdfdecbc109e8cb80b30afb30b72675b61c2f175 | DELETED |
| build/calendar_sft_v12_54_q4_work_20260911/calendar-assistant-v12.54-F16.gguf | 8051283296 | ed67169e22d4466beec2848c18659706df9889db9e582247b9172a8ead11d833 | DELETED |
| build/calendar_sft_qwen3_v12_55_gguf_20260912/calendar-assistant-v12.55-Q4_K_M.gguf | 2497278816 | 95e291c49c21ec3f897ee655da97d6678e518727ebc9b76d5d0b18ff0717f8f0 | DELETED |
| build/calendar_sft_v12_55_q4_work_20260912/calendar-assistant-v12.55-F16.gguf | 8051283296 | 606202490fd6ac0d5bd35839caddbb1aa55f9f8ef541847d9270910b078d3ae6 | DELETED |
| build/calendar_sft_qwen3_v12_56_gguf_20260913/calendar-assistant-v12.56-Q4_K_M.gguf | 2497278816 | 9cd4bf37aaa356a1c95550351285c897ae8104dfd79863823ca946ac569b13e1 | DELETED |
| build/calendar_sft_v12_56_q4_work_20260913/calendar-assistant-v12.56-F16.gguf | 8051283296 | 8f7a6f87971411b04e5ef7df7637fec0f203164d61c954dcb667a213e96a1b0c | DELETED |
| build/calendar_sft_qwen3_v12_57_gguf_20260914/calendar-assistant-v12.57-Q4_K_M.gguf | 2497278816 | 5f4b36143ca680ad14a2b46bb35171d8caea84902141464af207cfb990a0f7dc | DELETED |
| build/calendar_sft_v12_57_q4_work_20260914/calendar-assistant-v12.57-F16.gguf | 8051283296 | 9ef16ed66567332a665bb7c51f0b952e9c1c967e8c5e1528ae770d73b2d8b43c | DELETED |

## Проверка сохранности

[Полный машинный реестр](calendar_gguf_cleanup_2026_09_29.json) содержит снимок состояния 1329 сохраняемых файлов: пути, размеры, время изменения и SHA-256 там, где выполнялось хеширование.
У файлов до 8 МиБ и всех сохраняемых GGUF проверяется SHA-256. У остальных крупных файлов проверяются наличие, размер и время изменения; повторное чтение всех весов и состояний оптимизатора не выполняется.

Во время подготовки V12.61 находилась на этапе TRAINING. Её изменяемые каталоги запуска/контрольных точек, журналы запуска и CALENDAR_ASSISTANT_V12_61_TRAINING_RESULTS.md исключены из сравнения неизменности: их обновляет текущий процесс обучения. Они также полностью исключены из удаления. Подготовленные датасеты V12.61 и её скрипты входят в проверку SHA-256.

Проверено: 2026-09-28T21:31:41.5727494Z. Удалённых файлов: 19; объём удалённых файлов: 84149241760 байт. Сохраняемых файлов проверено: 1329, из них с SHA-256: 1214. Расхождения: 0.

## Сохранённые каталоги этапов обучения

Ссылки охватывают существующие каталоги данных, запусков, обучения, промежуточных сборок, выпусков и проверок; имена сохранены буквально. Окружение calendar_sft_local_gpu_venv также сохранено, но не включено в реестр результатов обучения.

- [build/calendar_sft_dataset](../build/calendar_sft_dataset/)
- [build/calendar_sft_dataset_v10](../build/calendar_sft_dataset_v10/)
- [build/calendar_sft_dataset_v11](../build/calendar_sft_dataset_v11/)
- [build/calendar_sft_dataset_v12](../build/calendar_sft_dataset_v12/)
- [build/calendar_sft_dataset_v13](../build/calendar_sft_dataset_v13/)
- [build/calendar_sft_dataset_v14](../build/calendar_sft_dataset_v14/)
- [build/calendar_sft_dataset_v2](../build/calendar_sft_dataset_v2/)
- [build/calendar_sft_dataset_v3](../build/calendar_sft_dataset_v3/)
- [build/calendar_sft_dataset_v4](../build/calendar_sft_dataset_v4/)
- [build/calendar_sft_dataset_v5](../build/calendar_sft_dataset_v5/)
- [build/calendar_sft_dataset_v6](../build/calendar_sft_dataset_v6/)
- [build/calendar_sft_dataset_v7](../build/calendar_sft_dataset_v7/)
- [build/calendar_sft_dataset_v9](../build/calendar_sft_dataset_v9/)
- [build/calendar_sft_holdout](../build/calendar_sft_holdout/)
- [build/calendar_sft_holdout_epoch1](../build/calendar_sft_holdout_epoch1/)
- [build/calendar_sft_holdout_epoch1_h45](../build/calendar_sft_holdout_epoch1_h45/)
- [build/calendar_sft_holdout_epoch2](../build/calendar_sft_holdout_epoch2/)
- [build/calendar_sft_local_epoch1](../build/calendar_sft_local_epoch1/)
- [build/calendar_sft_local_epoch1_model](../build/calendar_sft_local_epoch1_model/)
- [build/calendar_sft_local_epoch2_model](../build/calendar_sft_local_epoch2_model/)
- [build/calendar_sft_local_pilot](../build/calendar_sft_local_pilot/)
- [build/calendar_sft_models](../build/calendar_sft_models/)
- [build/calendar_sft_qwen3_v12_53_epoch1_20260910](../build/calendar_sft_qwen3_v12_53_epoch1_20260910/)
- [build/calendar_sft_qwen3_v12_53_gguf_20260910](../build/calendar_sft_qwen3_v12_53_gguf_20260910/)
- [build/calendar_sft_qwen3_v12_53_launch_20260910](../build/calendar_sft_qwen3_v12_53_launch_20260910/)
- [build/calendar_sft_qwen3_v12_54_epoch1_20260911](../build/calendar_sft_qwen3_v12_54_epoch1_20260911/)
- [build/calendar_sft_qwen3_v12_54_gguf_20260911](../build/calendar_sft_qwen3_v12_54_gguf_20260911/)
- [build/calendar_sft_qwen3_v12_54_launch_20260911](../build/calendar_sft_qwen3_v12_54_launch_20260911/)
- [build/calendar_sft_qwen3_v12_55_epoch1_20260912](../build/calendar_sft_qwen3_v12_55_epoch1_20260912/)
- [build/calendar_sft_qwen3_v12_55_gguf_20260912](../build/calendar_sft_qwen3_v12_55_gguf_20260912/)
- [build/calendar_sft_qwen3_v12_55_launch_20260912](../build/calendar_sft_qwen3_v12_55_launch_20260912/)
- [build/calendar_sft_qwen3_v12_56_epoch1_20260913](../build/calendar_sft_qwen3_v12_56_epoch1_20260913/)
- [build/calendar_sft_qwen3_v12_56_gguf_20260913](../build/calendar_sft_qwen3_v12_56_gguf_20260913/)
- [build/calendar_sft_qwen3_v12_56_launch_20260913](../build/calendar_sft_qwen3_v12_56_launch_20260913/)
- [build/calendar_sft_qwen3_v12_57_epoch1_20260914](../build/calendar_sft_qwen3_v12_57_epoch1_20260914/)
- [build/calendar_sft_qwen3_v12_57_gguf_20260914](../build/calendar_sft_qwen3_v12_57_gguf_20260914/)
- [build/calendar_sft_qwen3_v12_57_launch_20260914](../build/calendar_sft_qwen3_v12_57_launch_20260914/)
- [build/calendar_sft_qwen3_v12_6_epoch1_20260914](../build/calendar_sft_qwen3_v12_6_epoch1_20260914/)
- [build/calendar_sft_qwen3_v12_6_gguf_20260914](../build/calendar_sft_qwen3_v12_6_gguf_20260914/)
- [build/calendar_sft_qwen3_v12_6_launch_20260914](../build/calendar_sft_qwen3_v12_6_launch_20260914/)
- [build/calendar_sft_qwen3_v12_61_epoch1_20260928](../build/calendar_sft_qwen3_v12_61_epoch1_20260928/)
- [build/calendar_sft_qwen3_v12_61_launch_20260928](../build/calendar_sft_qwen3_v12_61_launch_20260928/)
- [build/calendar_sft_qwen3_v12_gguf_20260907](../build/calendar_sft_qwen3_v12_gguf_20260907/)
- [build/calendar_sft_qwen3_v12_merged_bf16_20260907](../build/calendar_sft_qwen3_v12_merged_bf16_20260907/)
- [build/calendar_sft_review](../build/calendar_sft_review/)
- [build/calendar_sft_v12_3_logs_20260909](../build/calendar_sft_v12_3_logs_20260909/)
- [build/calendar_sft_v12_3_preparation](../build/calendar_sft_v12_3_preparation/)
- [build/calendar_sft_v12_3_q4_work_20260909](../build/calendar_sft_v12_3_q4_work_20260909/)
- [build/calendar_sft_v12_53_q4_work_20260910](../build/calendar_sft_v12_53_q4_work_20260910/)
- [build/calendar_sft_v12_54_q4_work_20260911](../build/calendar_sft_v12_54_q4_work_20260911/)
- [build/calendar_sft_v12_55_q4_work_20260912](../build/calendar_sft_v12_55_q4_work_20260912/)
- [build/calendar_sft_v12_56_q4_work_20260913](../build/calendar_sft_v12_56_q4_work_20260913/)
- [build/calendar_sft_v12_57_q4_work_20260914](../build/calendar_sft_v12_57_q4_work_20260914/)
- [build/calendar_sft_v12_6_holdout_20260915](../build/calendar_sft_v12_6_holdout_20260915/)
- [build/calendar_sft_v12_6_q4_work_20260914](../build/calendar_sft_v12_6_q4_work_20260914/)
- [build/calendar_sft_v5_epoch1_20260827](../build/calendar_sft_v5_epoch1_20260827/)
- [build/calendar_sft_v5_holdout_20260827](../build/calendar_sft_v5_holdout_20260827/)
- [build/calendar_sft_v6_epoch1_20260827](../build/calendar_sft_v6_epoch1_20260827/)
- [build/calendar_sft_v6_holdout_20260827](../build/calendar_sft_v6_holdout_20260827/)
- [build/calendar_v12_61_checked_rebuild](../build/calendar_v12_61_checked_rebuild/)

## Сохранённая документация обучения

- [CALENDAR_ASSISTANT_ANDROID_MECHANISMS.md](CALENDAR_ASSISTANT_ANDROID_MECHANISMS.md)
- [CALENDAR_ASSISTANT_CLEAN_ROOM.md](CALENDAR_ASSISTANT_CLEAN_ROOM.md)
- [CALENDAR_ASSISTANT_DATASET_REVIEW.md](CALENDAR_ASSISTANT_DATASET_REVIEW.md)
- [CALENDAR_ASSISTANT_LAST_EVENT.md](CALENDAR_ASSISTANT_LAST_EVENT.md)
- [CALENDAR_ASSISTANT_ORDER_COVERAGE.md](CALENDAR_ASSISTANT_ORDER_COVERAGE.md)
- [CALENDAR_ASSISTANT_SFT_PILOT.md](CALENDAR_ASSISTANT_SFT_PILOT.md)
- [CALENDAR_ASSISTANT_TRAINING_SPEC.md](CALENDAR_ASSISTANT_TRAINING_SPEC.md)
- [CALENDAR_ASSISTANT_V12_1_TRAINING_RESULTS.md](CALENDAR_ASSISTANT_V12_1_TRAINING_RESULTS.md)
- [CALENDAR_ASSISTANT_V12_1.md](CALENDAR_ASSISTANT_V12_1.md)
- [CALENDAR_ASSISTANT_V12_2_GGUF_RESULTS.md](CALENDAR_ASSISTANT_V12_2_GGUF_RESULTS.md)
- [CALENDAR_ASSISTANT_V12_2_MONTH_DAYS_AB_RESULTS.md](CALENDAR_ASSISTANT_V12_2_MONTH_DAYS_AB_RESULTS.md)
- [CALENDAR_ASSISTANT_V12_2_TRAINING_RESULTS.md](CALENDAR_ASSISTANT_V12_2_TRAINING_RESULTS.md)
- [CALENDAR_ASSISTANT_V12_2.md](CALENDAR_ASSISTANT_V12_2.md)
- [CALENDAR_ASSISTANT_V12_3_TRAINING_RESULTS.md](CALENDAR_ASSISTANT_V12_3_TRAINING_RESULTS.md)
- [CALENDAR_ASSISTANT_V12_3.md](CALENDAR_ASSISTANT_V12_3.md)
- [CALENDAR_ASSISTANT_V12_4_NO_NOTES.md](CALENDAR_ASSISTANT_V12_4_NO_NOTES.md)
- [CALENDAR_ASSISTANT_V12_5_RELATIVE_CLOCK.md](CALENDAR_ASSISTANT_V12_5_RELATIVE_CLOCK.md)
- [CALENDAR_ASSISTANT_V12_51_ON_HOUR.md](CALENDAR_ASSISTANT_V12_51_ON_HOUR.md)
- [CALENDAR_ASSISTANT_V12_52_MINUTES_OF_HOUR.md](CALENDAR_ASSISTANT_V12_52_MINUTES_OF_HOUR.md)
- [CALENDAR_ASSISTANT_V12_53_COMPACT_CLOCK.md](CALENDAR_ASSISTANT_V12_53_COMPACT_CLOCK.md)
- [CALENDAR_ASSISTANT_V12_54_REPLY_MINUTES_TO.md](CALENDAR_ASSISTANT_V12_54_REPLY_MINUTES_TO.md)
- [CALENDAR_ASSISTANT_V12_55_HALF_HOUR.md](CALENDAR_ASSISTANT_V12_55_HALF_HOUR.md)
- [CALENDAR_ASSISTANT_V12_56_INTERVALS.md](CALENDAR_ASSISTANT_V12_56_INTERVALS.md)
- [CALENDAR_ASSISTANT_V12_61_PREPARATION.md](CALENDAR_ASSISTANT_V12_61_PREPARATION.md)
- [CALENDAR_ASSISTANT_V12_61_TRAINING_RESULTS.md](CALENDAR_ASSISTANT_V12_61_TRAINING_RESULTS.md)
- [CALENDAR_ASSISTANT_V12_GGUF_RESULTS.md](CALENDAR_ASSISTANT_V12_GGUF_RESULTS.md)
- [CALENDAR_ASSISTANT_V14_RESULTS.md](CALENDAR_ASSISTANT_V14_RESULTS.md)
- [CALENDAR_ASSISTANT_V6_MANUAL_AUDIT.md](CALENDAR_ASSISTANT_V6_MANUAL_AUDIT.md)
- [CALENDAR_ASSISTANT_V6_RESULTS.md](CALENDAR_ASSISTANT_V6_RESULTS.md)
- [CALENDAR_ASSISTANT_V7_RESULTS.md](CALENDAR_ASSISTANT_V7_RESULTS.md)
- [CALENDAR_ASSISTANT_V9_GGUF_RESULTS.md](CALENDAR_ASSISTANT_V9_GGUF_RESULTS.md)
- [CALENDAR_ASSISTANT_V9_RESULTS.md](CALENDAR_ASSISTANT_V9_RESULTS.md)
- [CALENDAR_DRAFT_CARDS.md](CALENDAR_DRAFT_CARDS.md)
- [CALENDAR_ENDS_AT.md](CALENDAR_ENDS_AT.md)
- [CALENDAR_OPTIONAL_NOTES.md](CALENDAR_OPTIONAL_NOTES.md)
- [CALENDAR_SFT_V12_6_DATA_AUDIT_2026_09_28.md](CALENDAR_SFT_V12_6_DATA_AUDIT_2026_09_28.md)
