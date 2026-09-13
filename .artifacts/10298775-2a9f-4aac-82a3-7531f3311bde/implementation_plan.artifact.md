# Изменение значения Top P по умолчанию

Задача: установить значение параметра `Top P` в настройках модели по умолчанию на `0.90`.

## Предложенные изменения

### Модели данных

#### [MODIFY] [GenerationParams.kt](file:///C:/Users/007/AndroidStudioProjects/AIAssistent1/app/src/main/java/com/example/aiassistent1/domain/model/GenerationParams.kt)
Изменение значения по умолчанию в data-классе `GenerationParams` с `0.8f` на `0.9f`.

### Репозиторий настроек

#### [MODIFY] [DataStoreSettingsRepository.kt](file:///C:/Users/007/AndroidStudioProjects/AIAssistent1/app/src/main/java/com/example/aiassistent1/data/repository/DataStoreSettingsRepository.kt)
Обновление значений по умолчанию при получении параметров из DataStore (в методе `getParamsForModel`).

## План проверки

### Автоматизированные тесты
- Сборка проекта для проверки отсутствия ошибок компиляции.

### Ручная проверка
- Зайти в настройки модели.
- Нажать "сброс настроек по умолчанию".
- Убедиться, что значение `Top P` установилось на `0.90`.
- Проверить, что для новых моделей (или при первом запуске) значение `Top P` равно `0.90`.
