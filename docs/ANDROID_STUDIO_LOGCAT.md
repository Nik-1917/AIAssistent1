# Android Studio: очистка logcat после закрытия приложения

В проект добавлена конфигурация запуска `Start app and clear logcat on exit`.

В Android Studio выберите её в списке конфигураций запуска и нажмите Run. Конфигурация запускает `tools/clear-logcat-on-app-exit.ps1`, который:

1. запускает `com.example.aiassistent1.MainActivity` через ADB;
2. ждёт завершения процесса приложения;
3. выполняет `adb logcat -c` только после завершения процесса.

Обычная конфигурация `app` для отладки остаётся доступной отдельно. Для корректной работы должен быть подключён ADB-девайс, а Android SDK должен быть доступен через `ANDROID_HOME`, `ANDROID_SDK_ROOT` или `PATH`.
