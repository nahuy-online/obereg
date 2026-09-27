# Руководство по сборке, отладке и выпуску

Документ рассчитан на человека на чистой машине: от клонирования репозитория
до запуска Android-сборки и тестов, без обращения к автору кода.

## 1. Предварительные требования

| ОС | Что нужно | Проверка |
|---|---|---|
| Все | Rust stable + rustup | `cargo --version`, `rustc --version` |
| Windows | MSVC Build Tools (Компоненты: «C++ build tools»), x64 | `where link.exe` (из Developer PowerShell) |
| macOS | Xcode Command Line Tools (`xcode-select --install`) | `xcrun --version` |
| Linux | `gcc`, `pkg-config`, `libssl-dev` (для сборочных утилит) | `gcc --version` |
| Android | Android SDK, NDK r26+ (переменная `ANDROID_NDK_HOME`), JDK 17 | `echo $ANDROID_NDK_HOME`, `java -version` |
| iOS | Xcode + `lipo` | `xcodebuild -version` |

Установка тулчейна Rust (Windows, PowerShell):

```powershell
winget install Rustlang.Rustup
rustup toolchain install stable --profile minimal --component rustfmt --component clippy
```

macOS/Linux:

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --component rustfmt --component clippy
```

> Файл `rust-toolchain.toml` в корне зафиксирует stable и сам доустановит `rustfmt` и `clippy`
> при первом запуске `cargo` в каталоге репозитория.

## 2. Первая сборка и тесты (все платформы)

```bash
git clone <repo-url> obereg
cd obereg

# собирает весь workspace: ядро, FFI, JNI, CLI
cargo build --workspace

# unit- и интеграционные тесты
cargo test --workspace

# строгая проверка перед коммитом
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
```

Windows-обёртка делает то же одной командой:

```powershell
./scripts/check.ps1
```

## 3. Сборка под платформы

| Платформа | Команда | Артефакт |
|---|---|---|
| Windows x86_64 | `scripts\build-desktop.ps1` | `target\release\obereg_ffi.dll`, `target\release\obereg-cli.exe` |
| Android (3 ABI) | `./scripts/build-android.sh` | `android/app/src/main/jniLibs/<abi>/libobereg_jni.so` |
| iOS (arm64 + x86_64) | `./scripts/build-ios.sh` | `libobereg_ffi.a` (universal, `lipo -info` подтверждает) |
| macOS (arm64 + x86_64) | вручную, см. ниже | `libobereg_ffi.dylib` (universal) |

macOS вручную:

```bash
rustup target add aarch64-apple-darwin x86_64-apple-darwin
cargo build --release -p obereg-ffi --target aarch64-apple-darwin
cargo build --release -p obereg-ffi --target x86_64-apple-darwin
lipo -create -output libobereg_ffi.dylib \
  target/aarch64-apple-darwin/release/libobereg_ffi.dylib \
  target/x86_64-apple-darwin/release/libobereg_ffi.dylib
```

Android вручную (если не хочется пользоваться скриптом):

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --locked
export ANDROID_NDK_HOME=/path/to/ndk
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o android/app/src/main/jniLibs build --release -p obereg-jni
```

## 4. Сборка Android-оболочки

```bash
cd android
gradle wrapper --gradle-version 8.7   # один раз, создаёт ./gradlew
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Важно: до сборки APK положите `libobereg_jni.so` в `android/app/src/main/jniLibs/<abi>/`.
Без библиотеки приложение не падает — показывает понятное сообщение о том, что ядро не собрано.

Эмулятор x86_64 использует `jniLibs/x86_64`; устройство — `arm64-v8a` (или `armeabi-v7a` для старых).

## 5. Демонстрация прохождения трафика

Приёмка на Android одной командой:

```powershell
.\scripts\acceptance-android.ps1
```

Сценарий ставит APK, запускает приложение и сервис туннеля, ждёт событие `tunnel_start`
в журнале на устройстве, выгружает журнал и проверяет, что он переживает перезапуск.
Без устройства завершается с кодом 2 и подсказкой — это проверено.

Десктоп (без Android, самый быстрый способ убедиться в работоспособности ядра):

```bash
cargo run --release -p obereg-cli -- demo
```

Ожидаемый результат: `ДОКАЗАТЕЛЬСТВО ТУННЕЛЯ: ДА`. Признаки, которые проверяет команда:

1. `via=tunnel` в результате пробы;
2. `observed_peer` — адрес локального прокси (`127.0.0.1:<port>`), а не адрес сервера;
3. сервер видит **другой** сокет (тот, который открыл прокси), чем соединение ядра.

Android: кнопка «Проверить трафик» делает два HTTP-запроса — через локальный SOCKS5-прокси ядра
и напрямую, и показывает оба внешних адреса. Через туннель адрес должен отличаться,
если настроен внешний восходящий узел; на MVP без сервера различие подтверждается
маршрутом пробы и журналом.

## 6. Отладка

| Задача | Как |
|---|---|
| Подробный журнал | `log_level: "debug"` (или `trace`) в конфигурации ядра |
| Где журнал на Android | `context.getFilesDir()/journal.jsonl` (в приложении — кнопка «Журнал») |
| Где журнал на десктопе | путь из `journal_path`; `:memory:` отключает запись на диск |
| Просмотр журнала | `cargo run -p obereg-cli -- journal --config config.json --limit 100` |
| Логи нативного слоя Android | `adb logcat -s OberegCore` (сообщения `obereg-jni`) |
| Проверка порта прокси | в приложении поле `listen_addr` в статусе; на десктопе — вывод `demo`/`start` |
| Типичная ошибка Windows | `os error 10035` при чтении из принятого сокета — сокет наследует неблокирующий режим; в движке это уже исправлено (`set_nonblocking(false)`) |

## 7. Бенчмарк

```bash
cargo run --release -p obereg-cli -- bench --iterations 30 --out bench/baseline.windows.json
```

Методика, пороги и формат отчёта — в `docs/bench/README.md`.

## 8. Выпуск

Чеклист перед тегом:

1. `cargo fmt --all -- --check` — чисто;
2. `cargo clippy --workspace --all-targets -- -D warnings` — чисто;
3. `cargo test --workspace` — все тесты зелёные;
4. baseline бенчмарка пересчитан и закоммичен (`bench/baseline.windows.json`);
5. `docs/module-registry.json` и `CHANGELOG.md` обновлены;
6. CI прогоняет все четыре платформы (см. `.github/workflows/ci.yml`).

Что **не** входит в выпуск этого этапа: подписание сборок для сторов, публикация в Google Play,
App Store и магазинах для ПК, магазинная оплата.

## 9. Воспроизведение на чистой машине: чеклист проверяющего

1. Клонировать репозиторий.
2. Выполнить раздел 2 — сборка и тесты.
3. Выполнить `cargo run --release -p obereg-cli -- demo` и убедиться в строке `ДОКАЗАТЕЛЬСТВО ТУННЕЛЯ: ДА`.
4. Собрать Android: `./scripts/build-android.sh`, затем `cd android && ./gradlew :app:assembleDebug`.
5. Пропущенные или неточные шаги зафиксировать как правку в этом файле.

## 10. Восстановление тулчейна Rust после сбоя

Симптомы: `cargo`/`rustc` не запускаются или сообщают, что компонент «не применим к тулчейну»;
файлы в `%USERPROFILE%\.cargo\bin` имеют размер 0 байт; `rustup` отсутствует.

Причина: исполняемые файлы были обнулены системной защитой окружения (типично для антивируса/EDR
при ложном срабатывании на свежескачанные бинарники) либо установка была прервана в середине.

Порядок восстановления (выполняется вручную):

1. Проверить журнал защитника: «Безопасность Windows» → «Защита от вирусов и угроз» → «Журнал защиты».
   Если там есть записи про `cargo.exe`, `rustc.exe` или `rustup-init.exe` — нужно добавить исключения
   (шаг 2) и ставить заново из чистого состояния.
2. Добавить исключения защитника на каталоги (PowerShell от администратора):

```powershell
Add-MpPreference -ExclusionPath "$env:USERPROFILE\.cargo"
Add-MpPreference -ExclusionPath "$env:USERPROFILE\.rustup"
Add-MpPreference -ExclusionPath "<путь к репозиторию>\target"
```

   Если на машине сторонний EDR — исключения добавляет его администратор.
3. Полностью удалить сломанную установку (файлы уже частично обнулены):

```powershell
Remove-Item -Recurse -Force "$env:USERPROFILE\.cargo"
Remove-Item -Recurse -Force "$env:USERPROFILE\.rustup"
```

   и убрать из переменной среды `Path` запись `%USERPROFILE%\.cargo\bin` (после переустановки она появится снова).
4. Установить заново:

```powershell
curl.exe -sSL -o "$env:TEMP\rustup-init.exe" https://static.rust-lang.org/rustup/dist/x86_64-pc-windows-msvc/rustup-init.exe
& "$env:TEMP\rustup-init.exe" -y --profile minimal --default-toolchain stable --component rustfmt --component clippy
```

   MSVC Build Tools на целевой машине уже есть; на новой машине нужен компонент
   «Разработка классических приложений на C++» из Visual Studio Installer.
5. Проверить:

```powershell
cargo --version; rustc --version
```

6. Вернуться к разделу 2 этого руководства и прогнать сборку, тесты, демонстрацию и бенчмарк.

Не прерывать `rustup` во время установки: прерванная установка оставляет тулчейн в полуразобранном
состоянии (именно так выглядит «missing manifest in toolchain»).
