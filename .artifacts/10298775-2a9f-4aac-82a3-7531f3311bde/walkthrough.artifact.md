# Результаты задачи: Изменение значения Top P по умолчанию

Параметр `Top P` в настройках генерации модели теперь по умолчанию имеет значение `0.90`.

## Что было сделано

### Изменения в коде
1. **[GenerationParams.kt](file:///C:/Users/007/AndroidStudioProjects/AIAssistent1/app/src/main/java/com/example/aiassistent1/domain/model/GenerationParams.kt)**
   - Значение по умолчанию для `topP` в data-классе изменено с `0.8f` на `0.9f`. Это также автоматически обновило логику кнопки "Сброс настроек по умолчанию" в интерфейсе настроек.

2. **[DataStoreSettingsRepository.kt](file:///C:/Users/007/AndroidStudioProjects/AIAssistent1/app/src/main/java/com/example/aiassistent1/data/repository/DataStoreSettingsRepository.kt)**
   - Обновлены фолбэк-значения при чтении из `DataStore` для существующих моделей.
   - Обновлено начальное значение (`initialValue`) для `StateFlow` параметров.

## Верификация
- Проект успешно собран (`gradle assembleDebug`).
- Параметр `Top P` теперь будет предлагаться со значением `0.90` для всех новых моделей и при сбросе настроек.
