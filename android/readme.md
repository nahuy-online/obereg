# Android-оболочка Obereg

Минимальный клиент: запускает Rust-ядро, поднимает встроенный туннель, показывает состояние
и проверяет прохождение трафика. Системный `VpnService` **не используется**: туннель живёт
внутри процесса приложения (локальный SOCKS5-прокси ядра).

## Требования

- Android SDK 34, JDK 17, Gradle 8.7 (или Android Studio с встроенным Gradle).
- NDK r26+ и Rust для сборки `libobereg_jni.so`.
- `ANDROID_NDK_HOME` указывает на NDK.

## Порядок сборки

```bash
# 1. Нативная библиотека ядра (из корня репозитория)
./scripts/build-android.sh
#    результат: android/app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libobereg_jni.so

# 2. Обёртка Gradle (один раз)
cd android
gradle wrapper --gradle-version 8.7

# 3. APK
./gradlew :app:assembleDebug

# 4. Установка
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Вместо шага 1 можно скачать CI-артефакт `jniLibs` из workflow `ci` и распаковать его
в `android/app/src/main/jniLibs`.

Если библиотеки нет, приложение не падает: на экране появится сообщение
«Нативная библиотека libobereg_jni.so не найдена» с указанием команды сборки.

## Как устроено

| Файл | Ответственность |
|---|---|
| `app/src/main/java/app/obereg/core/CoreBridge.kt` | JNI-мост к ядру; безопасные обёртки вместо падений |
| `app/src/main/java/app/obereg/player/CoreHolder.kt` | одна сессия на приложение, конфигурация, разбор статуса и журнала |
| `app/src/main/java/app/obereg/player/TunnelService.kt` | foreground-сервис: держит туннель и нотификацию состояния |
| `app/src/main/java/app/obereg/player/MainActivity.kt` | экран: старт/стоп, смена режима, проверка трафика, журнал |

Конфигурация ядра собирается в `CoreHolder`:

```
journal_path = filesDir/journal.jsonl   (журнал переживает перезапуск приложения)
device_id    = UUID из SharedPreferences
mode         = direct | tunnel          (хранится в SharedPreferences)
```

## Как подтверждается прохождение трафика

1. Нажмите «Запустить туннель» и дождитесь `состояние: connected`, в поле
   «локальный прокси» появится `127.0.0.1:<порт>`.
2. Нажмите «Проверить трафик». Приложение делает два запроса к `http://api.ipify.org/`:
   через локальный SOCKS5-прокси ядра и напрямую, и показывает оба внешних адреса.
3. Нажмите «Журнал»: события `session_start`, `tunnel_start`, `traffic_probe`, `tunnel_stop`
   с временными метками. Журнал лежит в `filesDir/journal.jsonl` и сохраняется между запусками.

Замечание по MVP: внешний адрес изменится только при настроенном внешнем восходящем узле
(`upstream.kind = socks5|http_connect`). С прямым upstream подтверждением служит маршрут:
запрос идёт в локальный прокси ядра, что видно по `observed_peer` на десктопе
(`cargo run -p obereg-cli -- demo`) и по событиям журнала на устройстве.

## Проверенная локальная сборка (2026-09-22)

Нативная часть и APK собраны и проверены на машине разработки:

```powershell
# 1. Нативные библиотеки под три ABI (NDK r26d + cargo-ndk 4.1.2)
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o android/app/src/main/jniLibs build --release -p obereg-jni -p obereg-ffi

# 2. APK (JDK 17 + Android SDK 34 + Gradle 8.7)
$env:ANDROID_HOME="C:\android-sdk"
gradle -p android :app:assembleDebug
```

Результат:

| Артефакт | Значение |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 10 553 376 байт |
| Пакет / версия | `app.obereg.player`, versionName `0.1.0` |
| minSdk / targetSdk | 26 / 34 |
| Нативные библиотеки | `lib/{arm64-v8a,armeabi-v7a,x86_64}/libobereg_{jni,ffi}.so` |
| `native-code` (по данным aapt2) | arm64-v8a, armeabi-v7a, x86_64 |
| SHA-256 | `58BF78F58CCA1FD4222256043D92366DB06E1B2A68EE77C5A884280107C7C64F` |

## Экраны приложения

| Экран | Что делает |
|---|---|
| Приёмка (MainActivity) | Состояние ядра, автотест, запуск/остановка туннеля, смена режима, проверка трафика, живой журнал с устройства |
| Каталог (CatalogActivity) | Поиск и список видео из источника каталога ядра; переход в плеер |
| Плеер (PlayerActivity) | Воспроизведение видеофайла (Media3/ExoPlayer); маршрут через SOCKS5-прокси ядра |
| YouTube (YouTubeActivity) | Встроенный плеер YouTube; трафик WebView направляется в HTTP CONNECT-прокси ядра |

### Два входа в туннель

| Протокол | Кому нужен | Адрес |
|---|---|---|
| SOCKS5 | плеер (Media3 через OkHttp), внешние SDK | `CoreSession::local_proxy_addr` |
| HTTP CONNECT | WebView и браузерные движки: только они и умеют ходить через туннель | `CoreSession::local_http_proxy_addr` |

Экран YouTube включает прокси через `androidx.webkit.ProxyController`. Если туннель не поднят, Android младше 9 или WebView не поддерживает настройку прокси — видео идёт напрямую, а на экране написано, каким маршрутом оно пошло.

Плеер намеренно минимальный: он принимает ссылку на видеофайл и играет её через туннель.
Каталог, поиск, извлечение потоков, фильтрация рекламы и скачивание — следующий этап продукта;
в этом этапе они вне объёма.

## Приёмка на устройстве (шаг за вами)

Одной командой (рекомендуется):

```powershell
.\scripts\acceptance-android.ps1
```

Сценарий находит `adb`, проверяет наличие устройства, ставит APK, запускает приложение и сервис туннеля,
дожидается события `tunnel_start` в журнале, выгружает журнал с устройства и проверяет,
что он переживает перезапуск приложения. Без подключённого устройства завершается с кодом 2
и понятным сообщением — это проверено запуском.

Вручную, если нужно по шагам:

```powershell
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.obereg.player/.MainActivity
```

Далее на экране: «Запустить туннель» — дождаться `состояние: connected`, затем «Проверить трафик»
(два адреса: через туннель и напрямую) и «Журнал».

Журнал можно забрать без UI:

```powershell
adb shell run-as app.obereg.player cat files/journal.jsonl
```

## Отладка

```bash
adb logcat -s OberegCore          # сообщения нативного слоя
adb shell run-as app.obereg.player cat files/journal.jsonl
```
