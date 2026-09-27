# Obereg

Платформонезависимое ядро для медиаплеера со встроенным опциональным туннелем.
Один код на Rust — оболочки на Android, iOS, Windows и macOS.

**Текущий этап (MVP):** ядро, FFI-биндинги, Android-оболочка, CI под четыре платформы,
тесты, бенчмарк и документация. Серверная часть туннеля и протоколы обфускации —
следующий этап, в этом репозитории они зафиксированы только на уровне интерфейсов.

## Быстрый старт

```bash
# 1. Тулчейн
rustup toolchain install stable --profile minimal --component rustfmt --component clippy

# 2. Сборка и тесты (одна команда собирает весь workspace)
cargo build --workspace
cargo test --workspace

# 3. Сквозная проверка: локальный сервер + туннель + доказательства маршрута
cargo run --release -p obereg-cli -- demo

# 4. Бенчмарк инициализации ядра
cargo run --release -p obereg-cli -- bench --iterations 30 --out bench/baseline.windows.json
```

Windows-скрипты-обёртки: `scripts\check.ps1`, `scripts\build-desktop.ps1`, `scripts\bench.ps1`.
Полное руководство — [docs/build-guide.md](docs/build-guide.md).

## Структура

| Путь | Что это | Статус |
|---|---|---|
| `crates/obereg-core` | Ядро: `error`, `config`, `catalog`, `journal`, `state`, `transport`, `tunnel`, `session`, `version` | готово |
| `crates/obereg-ffi` | C ABI (`cdylib` + `staticlib`) и заголовок `include/obereg.h` | готово |
| `crates/obereg-jni` | JNI-обёртка `app.obereg.core.CoreBridge` для Android | готово |
| `crates/obereg-cli` | Десктоп-харнесс: `demo`, `bench`, `probe`, `journal` | готово |
| `crates/obereg-relay` | Референсный внешний узел туннеля (SOCKS5-эндпоинт, статус, журнал) | готово |
| `android/` | Оболочка Android: каталог, плеер (Media3 через туннель), экран приёмки, сервис туннеля | готово |
| `ios/` | Пример вызова ядра из Swift + module map | пример |
| `docs/adr/` | ADR-0001…0006: границы, транспорт, туннель, FFI, журнал, версионирование | готово |
| `docs/architecture.md` | Карта модулей, направление зависимостей, правила расширения | готово |
| `docs/interfaces.md` | Реестр публичных интерфейсов и политика версионирования | готово |
| `docs/module-registry.json` | Машинно-читаемый реестр модулей и их статусов | готово |
| `docs/next-stage-desktop-shell.md` | План ПК-оболочки поверх готового ядра | готово |
| `docs/verification-report.md` | Отчёт: что проверено, чем подтверждено, что осталось | готово |
| `docs/review-independent.md` | Независимое ревью ядра: 14 находок и их судьба | готово |
| `docs/relay-guide.md` | Внешний узел туннеля: запуск релея, конфигурация клиента, что готово | готово |
| `docs/bench/` | Методика бенчмарка и baseline | готово |

## Как это работает

Ядро не содержит UI и не обращается к платформенным API. Транспортная прослойка решает,
как доставить запрос: напрямую (`direct`) или через встроенный туннель (`tunnel`).
Туннель MVP — локальный SOCKS5-совместимый прокси на петле, который ходит наверх через
выбранный восходящий узел (`direct`, `socks5`, `http_connect`).

```
оболочка (Kotlin / Swift / CLI)
      │  FFI: C ABI или JNI
      ▼
  CoreSession ──► TransportRouter ──► DirectTransport
      │                │
      │                └────────────► TunnelTransport ──► LoopbackTunnel (SOCKS5)
      ├──► Journal (JSONL на диске)                                │
      └──► StateSnapshot (наблюдатели)                             ▼
                                                          upstream (direct/socks5/http_connect)
```

Ключевое свойство: плеер не знает про транспорт, а туннель не требует системного VPN —
он обслуживает только трафик приложения.

## Публичный API

Версия: `CORE_API_VERSION = "0.1.0"` (правила изменений — `docs/adr/0006-api-versioning.md`).

| Интерфейс | Назначение |
|---|---|
| `CoreSession::new(CoreConfig)` | создать сессию |
| `CoreSession::start() / stop()` | поднять/закрыть туннель и перевести состояние |
| `CoreSession::status() / status_json()` | снимок состояния |
| `CoreSession::set_transport_mode(mode)` | переключить `direct` / `tunnel` (в том числе на ходу) |
| `CoreSession::local_proxy_addr()` | адрес локального прокси для оболочек |
| `CoreSession::probe_traffic(url)` | проверка прохождения трафика |
| `CoreSession::events(limit) / events_json(limit)` | журнал событий |
| `CoreSession::catalog_search(query, limit)` | поиск в каталоге видео (источник — точка расширения) |
| `CoreSession::catalog_search_json(query, limit)` | тот же поиск в форме JSON для биндингов |
| `CoreSession::add_observer(observer)` | подписка на изменения состояния |

## Проверки

| Что | Команда |
|---|---|
| Формат и линтер | `cargo fmt --all -- --check`, `cargo clippy --workspace --all-targets -- -D warnings` |
| Тесты ядра | `cargo test --workspace` |
| Сквозная демонстрация туннеля | `cargo run --release -p obereg-cli -- demo` |
| Baseline запуска | `cargo run --release -p obereg-cli -- bench --out bench/baseline.windows.json` |
| Android JNI + APK | `scripts/build-android.sh` → `cd android && ./gradlew :app:assembleDebug` |
| iOS `libobereg_ffi.a` | `scripts/build-ios.sh` |
| Все четыре платформы | GitHub Actions: `.github/workflows/ci.yml` |

Актуальный статус проверок с датами и доказательствами — в [docs/verification-report.md](docs/verification-report.md).

## Чего в этом этапе нет

- серверной инфраструктуры туннеля, аккаунтов, биллинга и подписок;
- протоколов обфускации и выбора входных точек (только интерфейсы);
- готовых оболочек для iOS, Windows и macOS (только сборка ядра и биндинги);
- публикации в сторах и подписи релизных сборок.
