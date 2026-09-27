# Obereg — архитектура ядра

- Статус: принято (сводный архитектурный документ)
- Дата: 2026-09-22
- Область: crate `obereg-core` (Rust), взаимодействие с оболочками

Документ описывает платформонезависимое ядро Obereg: карту модулей, направление зависимостей,
публичный API, порядок добавления нового модуля, соответствие критериям приёмки и формат журнала
туннеля. Обоснования решений — в ADR 0001–0003.

---

## 1. Карта модулей ядра

| Модуль | Зона ответственности | Публичные интерфейсы | Запрещённые зависимости |
|--------|----------------------|----------------------|-------------------------|
| `error` | единый тип ошибки ядра, `Result`-алиас, маппинг в FFI | `CoreError`, `Result<T>` | любые внутренние модули ядра (лист); запрещены `transport`, `tunnel`, `session`, `journal`, `state`, `config` |
| `config` | конфигурация ядра и сериализация | `CoreConfig`, `TransportMode`, `UpstreamConfig`, `UpstreamKind`, `TunnelConfig`, `to_json`/`from_json`, `default_for` | `session`, `transport`, `tunnel`, `journal`, `state` |
| `journal` | события ядра, хранение и чтение журнала | `JournalEvent`, `JournalKind`, трейт `Journal`, `FileJournal`, `MemoryJournal` | `session`, `transport`, `tunnel`, `state` |
| `state` | состояние соединения, снапшоты, наблюдатели | `ConnectionState`, `StateSnapshot`, `StateObserver` | `session`, `transport`, `tunnel`, `journal` |
| `transport` | единая точка сетевых соединений, режимы и фолбэк | трейт `Transport`, `DirectTransport`, `TunnelTransport`, `TransportRouter`, `ProbeResult` | `session`; запрещены сетевые крейты (`tokio`/`reqwest`/`hyper`), UI-типы |
| `tunnel` | локальный прокси и форвардинг в upstream | трейт `TunnelEngine`, `LoopbackTunnel`, `TunnelHandle`, `Upstream*` | `session`; запрещены сетевые крейты, обфускация/DPI-техники вне интерфейсов |
| `session` | оркестрация: жизненный цикл, композиция модулей | `CoreSession` | платформенные API, `std::process`, UI-типы |
| `version` | версия публичного API ядра | `CORE_API_VERSION` | любые (лист, значение-константа) |

Общие запреты для **всего** ядра: `std::process`, платформенные API, сетевые крейты
(`tokio`/`reqwest`/`hyper`), UI-типы. Разрешённые зависимости ядра: `std`, `serde`, `serde_json`,
`thiserror`.

---

## 2. Направление зависимостей

```
                          ┌───────────┐
                          │  session  │
                          └─────┬─────┘
   ┌──────────┬───────────┬─────┴─────┬────────────┬─────────┐
   ▼          ▼           ▼           ▼            ▼         ▼
┌─────────┐┌─────────┐┌────────┐┌────────┐┌────────┐┌────────┐
│transport││ tunnel  ││ config ││ state  ││journal ││ error  │
└────┬────┘└────┬────┘└────────┘└───┬────┘└───┬────┘└────────┘
     │          │                   │         │
     ├──────────┴──> { config, state, error, journal }
     │
     │   state   ──> { config, error }
     │   journal ──> { config, error }
     ▼
  std / serde / serde_json / thiserror

  version ──> (нет зависимостей)
```

Формально:

- `session` → `{ transport, tunnel, config, state, journal, error }`
- `transport` / `tunnel` → `{ config, state, error, journal }`
- `state` → `{ config, error }`
- `journal` → `{ config, error }`
- `error` → `{ std, serde, thiserror }`
- `config` → `{ std, serde, serde_json }`
- `version` → ∅

Ниже уровня модулей — только `std` и `serde*`. Обратные зависимости между модулями ядра запрещены.
Межуровневые зависимости оболочки/ядра — по ADR 0001: `shell → bindings → ffi → core`, обратно никогда.

---

## 3. Публичный API ядра

Версия API: `CORE_API_VERSION = "0.1.0"`.

### 3.1. `CoreSession`

```rust
pub const CORE_API_VERSION: &str = "0.1.0";

impl CoreSession {
    pub fn new(cfg: CoreConfig) -> Result<CoreSession>;
    pub fn start(&self) -> Result<StateSnapshot>;
    pub fn stop(&self) -> Result<StateSnapshot>;
    pub fn status(&self) -> StateSnapshot;
    pub fn set_transport_mode(&self, mode: TransportMode) -> Result<StateSnapshot>;
    pub fn journal(&self) -> Arc<dyn Journal>;
    pub fn local_proxy_addr(&self) -> Option<String>;
    pub fn probe_traffic(&self, url: &str) -> Result<TrafficProbe>;
}

pub struct TrafficProbe {
    pub url: String,
    pub via: String,          // каким транспортом/путём проверялось
    pub status: u16,          // HTTP-статус
    pub body_len: usize,
    pub elapsed_ms: u64,
    pub observed_peer: String, // наблюдаемый удалённый узел
}
```

### 3.2. `config`

```rust
pub enum TransportMode { Direct, Tunnel }

pub enum UpstreamKind { Direct, Socks5, HttpConnect }

pub struct UpstreamConfig {
    pub kind: UpstreamKind,
    pub addr: String,
    pub username: Option<String>,
    pub password: Option<String>,
}

pub struct TunnelConfig {
    pub listen_addr: String,
    pub mode: TransportMode,
    pub upstream: UpstreamConfig,
    pub handshake_timeout_ms: u64,
    pub idle_timeout_ms: u64,
}

pub struct CoreConfig {
    pub config_version: u32,
    pub device_id: String,
    pub locale: String,
    pub log_level: String,
    pub journal_path: String,
    pub tunnel: TunnelConfig,
}

impl CoreConfig {
    pub fn default_for(device_id: &str, journal_path: &str) -> CoreConfig;
    pub fn to_json(&self) -> Result<String>;
    pub fn from_json(s: &str) -> Result<CoreConfig>;
}
```

### 3.3. `journal`

```rust
pub enum JournalKind {
    TunnelStart,
    TunnelStop,
    TunnelError,
    TransportSwitch,
    SessionStart,
    SessionStop,
    TrafficProbe,
    ConfigChanged,
}

pub struct JournalEvent {
    pub ts_ms: u64,
    pub seq: u64,
    pub kind: JournalKind,
    pub session_id: String,
    pub message: String,
    pub fields: serde_json::Value,
}

pub trait Journal {
    fn append(&self, ev: JournalEvent) -> Result<()>;
    fn read_all(&self) -> Result<Vec<JournalEvent>>;
}

pub struct FileJournal;   // JSONL на диске, append, переживает перезапуск
pub struct MemoryJournal; // в памяти, для тестов
```

### 3.4. `state`

```rust
pub enum ConnectionState {
    Idle,
    Starting,
    Connected,
    Degraded,
    Stopping,
    Failed,
}

pub struct StateSnapshot {
    pub state: ConnectionState,
    pub mode: TransportMode,
    pub since_ms: u64,
    pub last_error: Option<String>,
    pub listen_addr: Option<String>,
    pub bytes_up: u64,
    pub bytes_down: u64,
    pub sessions_served: u64,
}

pub trait StateObserver {
    fn on_state(&self, snapshot: &StateSnapshot);
}
```

---

## 4. Как добавить новый модуль в ядро

Пошагово, без устных пояснений:

1. **Создать crate или модуль.** Предпочесть модуль внутри `obereg-core`; отдельный crate — только
   если нужна независимая сборка. Никаких зависимостей вне разрешённого списка (`std`, `serde`,
   `serde_json`, `thiserror`).
2. **Определить трейт.** Публичный интерфейс модуля оформляется трейтом; конкретная реализация
   подставляется в `session`. Прямые вызовы соседних модулей в обход трейта не допускаются.
3. **Зарегистрировать в `session`.** `CoreSession` создаёт и держит компонент, при необходимости
   вызывает его в `start`/`stop`/`status`. Публичный доступ — только через методы `CoreSession`.
4. **Добавить запись в `module-registry.json`.** Указать имя модуля, зону ответственности, публичные
   интерфейсы, разрешённые/запрещённые зависимости и владельца.
5. **Покрыть тестами.** Юнит-тесты модуля + интеграционный тест сценария через `CoreSession`. Модуль
   не считается готовым без тестов, работающих без платформы и без UI.
6. **Обновить ADR.** Зафиксировать решение (новый ADR или правка связанного), обновить карту модулей
   и направление зависимостей в этом документе.

Проверка перед мержем: направление зависимостей не нарушено; список зависимостей не расширен;
`module-registry.json` и ADR обновлены; тесты проходят в CI без сети/UI.

---

## 5. Соответствие критериям приёмки

| Критерий | Где реализовано |
|----------|-----------------|
| Ядро не зависит от UI и платформенных API | запреты зависимостей (`error`/`config`/… ), ADR 0001; `core` использует только `std`/`serde`/`thiserror` |
| Публичный API стабилен и версионирован | `version::CORE_API_VERSION`, раздел «Публичный API ядра» |
| Оболочка запускает и останавливает ядро | `CoreSession::new/start/stop`, `StateSnapshot` |
| Режим транспорта переключается | `CoreSession::set_transport_mode`, `TransportMode` |
| Есть автоматический аварийный откат `Tunnel → Direct` | `transport::TransportRouter`, ADR 0002 |
| Туннель опционален | `TransportMode::{Direct,Tunnel}`, `DirectTransport`, ADR 0002 |
| Есть локальный прокси и форвард в upstream | `tunnel::LoopbackTunnel`, `Upstream*`, ADR 0003 |
| Оболочка получает адрес локального прокси | `CoreSession::local_proxy_addr()` |
| Трафик проверяется сквозным тестом | `CoreSession::probe_traffic` → `TrafficProbe`, ADR 0003 |
| Состояние наблюдаемо и переживает запросы статуса | `StateSnapshot`, `StateObserver`, `ConnectionState::Degraded` |
| Журнал переживает перезапуск и читается оболочкой | `journal::FileJournal` (JSONL, append), `CoreSession::journal()` |
| Конфигурация сериализуема | `CoreConfig::to_json`/`from_json`, `default_for` |

---

## 6. Журнал туннеля

- **Формат:** JSONL — один `JournalEvent` на строку.
- **Запись:** только append; существующие записи не перезаписываются.
- **Хранение:** файл на диске (`CoreConfig.journal_path`), содержимое сохраняется между запусками
  процесса.
- **Чтение оболочкой:** через `CoreSession::journal() -> Arc<dyn Journal>`, метод `read_all()`.
- **Порядок и время:** `seq` монотонно возрастает, `ts_ms` — метка времени события.
- **Тестовый режим:** `MemoryJournal` повторяет контракт `Journal` без файловой системы.

События, относящиеся к туннелю и транспорту: `TunnelStart`, `TunnelStop`, `TunnelError`,
`TransportSwitch`, `TrafficProbe`. Жизненный цикл сессии и конфига: `SessionStart`, `SessionStop`,
`ConfigChanged`.

Пример строки JSONL:

```json
{"ts_ms":1758531600000,"seq":12,"kind":"TransportSwitch","session_id":"dev-01","message":"tunnel->direct fallback","fields":{"from":"tunnel","to":"direct","reason":"handshake_timeout"}}
```

---

## 7. Связанные документы

- `docs/adr/0001-core-shell-boundary.md` — граница «ядро / оболочка».
- `docs/adr/0002-transport-modes.md` — транспортная прослойка с двумя режимами.
- `docs/adr/0003-tunnel-engine.md` — туннельный движок MVP.
