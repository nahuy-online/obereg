# interfaces.md — реестр публичных интерфейсов ядра Obereg

- Версия API: `0.1.0` (`CORE_API_MAJOR = 0`), см. ADR [0006-api-versioning.md](adr/0006-api-versioning.md).
- Источник истины: крейт `obereg-core` (модули `error`, `config`, `journal`, `state`, `transport`, `tunnel`, `session`, `version`); документ сверяется с кодом и `module-registry.json`.
- Обозначения: `Result<T>` = `Result<T, CoreError>`; код и идентификаторы — английские, текст — русский.
- Статус сверки: модули `error/config/journal/state/transport/tunnel/version` соответствуют коду `0.1.0`; `session` приведён по контракту `lib.rs` и плану (файл `src/session.rs` на момент написания ещё не в дереве).

## 1. Версия API

| Элемент | Тип | Значение | Семантика |
|---|---|---|---|
| `CORE_API_VERSION` | `&'static str` | `"0.1.0"` | Полная версия `MAJOR.MINOR.PATCH` публичного API ядра |
| `CORE_API_MAJOR` | `u32` | `0` | Мажорная версия; дублируется в `module-registry.json` и в статусе сессии |

- Значения задаются один раз в `src/version.rs`; расхождение с `module-registry.json` — дефект сборки (см. ADR-0006).

## 2. Ошибки: `CoreError`, `Result<T>`

```rust
pub enum CoreError {
    Config(String),   // некорректная/несовместимая конфигурация (JSON, адреса, инварианты)
    Io(String),       // ошибки ввода-вывода ОС (файлы, сокеты)
    Tunnel(String),   // сбой туннельного движка: bind/accept/upstream
    Transport(String),// сбой транспорта: connect/probe/режим недоступен
    Journal(String),  // ошибка чтения/записи журнала
    AlreadyRunning,   // повторный start() при активной сессии
    NotRunning,       // stop()/действие при неактивной сессии
}
pub type Result<T> = std::result::Result<T, CoreError>;
impl From<std::io::Error> for CoreError;      // -> Io
impl From<serde_json::Error> for CoreError;   // -> Config("json: ...")
```

Семантика и инварианты:
- `CoreError` реализует `std::error::Error` (через `thiserror`) и `Display`; наружу из FFI паники не выходят.
- FFI **не транслирует варианты** отдельными кодами (стабильность ABI важнее детализации): контракт по кодам возврата — в §9.
- Текст ошибки для оболочки — через `obereg_core_last_error` (§9).

## 3. Конфигурация (JSON-контракт биндингов)

### `CoreConfig`

```rust
pub struct CoreConfig {
    pub config_version: u32,        // версия схемы; CURRENT_CONFIG_VERSION = 1
    pub device_id: String,          // генерирует оболочка, попадает в журнал
    pub locale: String,             // default "ru"; ядро только хранит
    pub log_level: LogLevel,        // default Info
    pub journal_path: String,       // ":memory:" включает журнал в памяти
    pub probe_target: String,       // default "1.1.1.1:443"
    pub tunnel: TunnelConfig,
}
impl CoreConfig {
    pub const CURRENT_CONFIG_VERSION: u32 = 1;
    pub fn default_for(device_id: impl Into<String>, journal_path: impl Into<String>) -> Self; // mode = direct
    pub fn default_tunneled(device_id: impl Into<String>, journal_path: impl Into<String>) -> Self; // mode = tunnel
    pub fn to_json(&self) -> String;
    pub fn to_json_pretty(&self) -> String;
    pub fn from_json(json: &str) -> Result<Self>;   // парсинг + validate()
    pub fn validate(&self) -> Result<()>;
    pub fn effective_upstream(&self) -> UpstreamConfig; // None => UpstreamConfig::direct()
}
```

Сеpializация: snake_case, имена полей стабильны. Инварианты `validate()`:
- `config_version <= CURRENT_CONFIG_VERSION` (более новая схема — `Config`);
- `journal_path` и `device_id` непусты;
- `tunnel.listen_addr` разбирается как `SocketAddr`;
- `tunnel.handshake_timeout_ms > 0`;
- при `upstream.kind != Direct` поле `addr` непусто и имеет вид `host:port`.

### `TunnelConfig`

```rust
pub struct TunnelConfig {
    pub listen_addr: String,              // default "127.0.0.1:0" (свободный порт)
    pub mode: TransportMode,              // default Direct
    pub upstream: Option<UpstreamConfig>, // None эквивалентен kind = direct
    pub handshake_timeout_ms: u64,        // default 5000
    pub idle_timeout_ms: u64,             // default 30000
}
```

### `UpstreamConfig`, `UpstreamKind`

```rust
pub struct UpstreamConfig {
    pub kind: UpstreamKind,
    pub addr: String,             // "host:port"; игнорируется для direct
    pub username: Option<String>,
    pub password: Option<String>,
}
impl UpstreamConfig { pub fn direct() -> Self; }

pub enum UpstreamKind { Direct, Socks5, HttpConnect } // Default = Direct
```

- JSON-значения `UpstreamKind`: `"direct"`, `"socks5"`, `"httpconnect"` (serde `rename_all = "lowercase"` — единый регистр, без `_`). В сообщениях об ошибках используется написание `http_connect` — расхождение см. открытые вопросы.

### `TransportMode`, `LogLevel`

```rust
pub enum TransportMode { Direct, Tunnel }              // Default = Direct; JSON "direct"|"tunnel"
pub enum LogLevel { Trace, Debug, Info, Warn, Error }  // Default = Info; JSON lowercase
impl std::fmt::Display for TransportMode;              // "direct" | "tunnel"
impl std::str::FromStr for TransportMode;              // Err = CoreError::Config
```

- `TransportMode::from_str` принимает `direct|tunnel` (регистр игнорируется, пробелы обрезаются); иное — `CoreError::Config`.

## 4. Журнал

### `Journal`, `FileJournal`, `MemoryJournal`

```rust
pub trait Journal: Send + Sync {
    fn append(&self, event: &JournalEvent) -> Result<u64>;  // возвращает назначенный seq
    fn read_all(&self) -> Result<Vec<JournalEvent>>;        // по возрастанию seq
    fn location(&self) -> Option<String> { None }           // путь к файлу, если файловый
}
impl MemoryJournal { pub fn new() -> Self; pub fn len(&self) -> usize; pub fn is_empty(&self) -> bool; }
impl FileJournal { pub fn open(path: impl AsRef<Path>) -> Result<Self>; pub fn path(&self) -> &Path; }
pub fn now_ms() -> u64; // мс от эпохи Unix
```

- `Journal::append` потокобезопасен; `seq` назначает журнал (ручной `seq` во входном событии игнорируется).
- `FileJournal::open` создаёт родительские каталоги и восстанавливает `seq` как максимум валидных `seq` в файле; повреждённая строка не ломает чтение.
- `MemoryJournal` используется при `journal_path = ":memory:"` и в бенчмарке (без диска).
- `read_all` в MVP читает весь файл; сортировка по `seq` при чтении.
- Инвариант: журнал append-only, события не удаляются; `seq` строго монотонен (см. ADR-0005).

### `JournalEvent`, `JournalKind`

```rust
pub struct JournalEvent {
    pub ts_ms: u64,                       // 0 => журнал подставит now_ms()
    pub seq: u64,                         // назначает журнал
    pub kind: JournalKind,
    pub session_id: String,               // обязательное поле (не Option)
    pub message: String,
    pub fields: BTreeMap<String, String>, // default пусто
}
impl JournalEvent {
    pub fn new(kind: JournalKind, session_id: impl Into<String>, message: impl Into<String>) -> Self;
    pub fn with_field(self, key: impl Into<String>, value: impl Into<String>) -> Self;
    pub fn to_json_line(&self) -> String; // одна строка без перевода строки
}

pub enum JournalKind {   // serde snake_case: "tunnel_start", ...
    TunnelStart, TunnelStop, TunnelError,
    TransportSwitch, SessionStart, SessionStop,
    TrafficProbe, ConfigChanged,
}
```

| Kind | Когда | Типовые `fields` |
|---|---|---|
| `TunnelStart` | успешный старт туннеля | `listen_addr`, `engine` |
| `TunnelStop` | остановка туннеля | `listen_addr` |
| `TunnelError` | ошибка туннеля/клиента/upstream | — |
| `TransportSwitch` | смена `TransportMode` | `from`, `to` |
| `SessionStart` / `SessionStop` | жизненный цикл `CoreSession` | `device_id` / `seq` |
| `TrafficProbe` | результат `probe_traffic` | `via`, `status`, `observed_peer` |
| `ConfigChanged` | применение нового конфига | `source` |

## 5. Состояние

### `ConnectionState`, `StateSnapshot`

```rust
pub enum ConnectionState { Idle, Starting, Connected, Degraded, Stopping, Failed } // JSON lowercase; начальное — Idle
impl std::fmt::Display for ConnectionState; // "idle"|"starting"|"connected"|"degraded"|"stopping"|"failed"

pub struct StateSnapshot {
    pub state: ConnectionState,
    pub mode: TransportMode,
    pub since_ms: u64,                 // момент последнего перехода
    pub last_error: Option<String>,
    pub listen_addr: Option<String>,   // адрес локального прокси, если туннель поднят
    pub bytes_up: u64,
    pub bytes_down: u64,
    pub sessions_served: u64,
}
impl StateSnapshot { pub fn initial(mode: TransportMode) -> Self; pub fn to_json(&self) -> String; }
```

Переходы автомата (допустимые):

| Из | В | Условие |
|---|---|---|
| `Idle` | `Starting` | `start()` |
| `Starting` | `Connected` | туннель поднят, канал проверен |
| `Starting` | `Failed` | неустранимая ошибка старта |
| `Connected` | `Degraded` | туннель не поднялся, сработал откат на `direct` |
| `Degraded` | `Connected` | восстановление туннеля |
| `Connected`/`Degraded` | `Stopping` | `stop()` |
| `Stopping` | `Idle` | завершение остановки |
| `Failed` | `Idle` | повторный `start()` сбрасывает состояние |

- Инвариант: `Degraded`/`Failed` не переходят в `Connected` без промежуточной проверки канала.

### `StateObserver`

```rust
pub trait StateObserver: Send + Sync {
    fn on_state(&self, snapshot: &StateSnapshot);
}
```

- Вызывается синхронно из потока, инициировавшего переход; реализация не должна блокироваться.

## 6. Транспорт

```rust
pub trait Connection: Read + Write + Send {           // открытое соединение
    fn peer_addr(&self) -> Option<SocketAddr>;        // для tunnel — 127.0.0.1:port (доказательство маршрута)
    fn set_read_timeout(&self, timeout: Option<Duration>) -> Result<()>;
    fn set_write_timeout(&self, timeout: Option<Duration>) -> Result<()>;
    fn describe(&self) -> String;                     // короткое описание для журнала
}
impl Connection for TcpStream { /* ... */ }

pub trait Transport: Send + Sync {
    fn name(&self) -> &'static str;                                   // "direct" | "tunnel"
    fn mode(&self) -> TransportMode;
    fn probe(&self) -> ProbeResult;                                   // проверка канала, без полезного соединения
    fn connect(&self, target: &str, port: u16) -> Result<Box<dyn Connection>>;
}

pub struct ProbeResult { pub ok: bool, pub elapsed_ms: u64, pub detail: String }
impl ProbeResult { pub fn ok(elapsed_ms: u64, detail: impl Into<String>) -> Self;
                   pub fn err(elapsed_ms: u64, detail: impl Into<String>) -> Self; }

pub const DEFAULT_PROBE_TARGET: &str = "1.1.1.1:443"; // модуль transport::direct
impl DirectTransport {
    pub fn new() -> Self;                          // probe target 1.1.1.1:443, timeout 3s
    pub fn with_probe_target(target: impl Into<String>) -> Self;
    pub fn with_timeout(self, timeout: Duration) -> Self;
    pub fn probe_target(&self) -> &str;
}
impl TunnelTransport {
    pub fn new() -> Self;                          // туннель не поднят
    pub fn set_proxy_addr(&self, addr: Option<String>); // задаёт сессия при старте/остановке
    pub fn proxy_addr(&self) -> Option<String>;
    pub fn set_timeout(&mut self, timeout: Duration);
}
pub struct RoutedConnection { pub connection: Box<dyn Connection>, pub mode: TransportMode, pub fell_back: bool }
impl TransportRouter {
    pub fn new(direct: DirectTransport, tunnel: TunnelTransport, initial: TransportMode) -> Self;
    pub fn direct(&self) -> &DirectTransport;
    pub fn tunnel(&self) -> &TunnelTransport;
    pub fn auto_fallback(&self) -> bool;
    pub fn set_auto_fallback(&self, enabled: bool);
    pub fn active_mode(&self) -> TransportMode;
    pub fn set_mode(&self, mode: TransportMode) -> Result<()>;   // tunnel без прокси => Transport
    pub fn probe_active(&self) -> ProbeResult;
    pub fn connect(&self, target: &str, port: u16) -> Result<RoutedConnection>;
}
```

- `Transport::connect` возвращает трейт-объект `Connection`; адрес `peer_addr()` — единственный публичный признак фактического маршрута.
- `TransportRouter`: при активном `tunnel` и недоступном туннеле с включённым `auto_fallback` молча уходит в `direct` и выставляет `RoutedConnection::fell_back = true`; при выключенном — возвращает исходную ошибку.
- `set_mode(Tunnel)` требует поднятого туннеля (`TunnelTransport::proxy_addr().is_some()`), иначе `CoreError::Transport` и режим не меняется.
- `DirectTransport::new()` и `TunnelTransport::new()` не занимают портов и не открывают соединений.

## 7. Туннель

```rust
pub trait TunnelEngine: Send + Sync {
    fn name(&self) -> &'static str;  // "loopback-socks5"
    fn start(&self, config: &TunnelConfig, journal: Arc<dyn Journal>, session_id: &str) -> Result<TunnelHandle>;
}
pub struct LoopbackTunnel;                            // MVP: локальный SOCKS5-совместимый прокси
impl LoopbackTunnel { pub fn new() -> Self; }

pub struct TunnelStats {                              // атомарные счётчики
    pub bytes_up: AtomicU64, pub bytes_down: AtomicU64, pub sessions_served: AtomicU64,
}
impl TunnelStats { pub fn snapshot(&self) -> (u64, u64, u64); }

pub struct TunnelHandle;                              // дескриптор запущенного туннеля
impl TunnelHandle {
    pub fn listen_addr(&self) -> &str;
    pub fn stats(&self) -> Arc<TunnelStats>;
    pub fn stats_snapshot(&self) -> (u64, u64, u64);
    pub fn is_running(&self) -> bool;
    pub fn stop(&mut self);                           // сигнал + join потока приёма
}
impl Drop for TunnelHandle;                           // гарантированная остановка
```

- `start` занимает `config.listen_addr` (`127.0.0.1:0` — свободный порт), пишет `TunnelStart` и запускает поток приёма; ошибка bind — `Tunnel`.
- Клиенты обслуживаются в отдельных потоках; ошибка клиента пишется как `TunnelError` и не роняет движок.
- `stop`/`Drop` останавливают приём; `TunnelHandle` недействителен после `stop`.
- Точка расширения: новый протокол обхода — новая реализация `TunnelEngine`, код выше не меняется.

## 8. Сессия: `CoreSession`, `TrafficProbe`

```rust
pub struct CoreSession { /* фасад: конфиг, туннель, транспорт, журнал, состояние */ }
impl CoreSession {
    pub fn new(config: CoreConfig) -> Result<Self>;
    pub fn start(&mut self) -> Result<StateSnapshot>;                 // Idle/Failed -> Starting -> Connected|Degraded
    pub fn stop(&mut self) -> Result<()>;                             // -> Stopping -> Idle
    pub fn status(&self) -> StateSnapshot;                            // снимок без блокировок
    pub fn set_transport_mode(&mut self, mode: TransportMode) -> Result<()>; // + TransportSwitch
    pub fn journal(&self) -> Arc<dyn Journal>;
    pub fn local_proxy_addr(&self) -> Option<String>;                 // Some после успешного start()
    pub fn probe_traffic(&self, url: &str) -> Result<TrafficProbe>;
}
pub struct TrafficProbe {
    pub url: String,
    pub via: TransportMode,
    pub status: Option<u16>,           // None = соединение не установлено
    pub body_len: Option<usize>,
    pub elapsed_ms: u64,
    pub observed_peer: Option<String>, // адрес, с которого фактически пришёл ответ
}
```

Семантика (контракт; сверка с кодом `session.rs` — в открытых вопросах):
- `new` валидирует конфиг (`Config`), но не открывает портов и не запускает потоков.
- `start` на активной сессии — `AlreadyRunning`; `stop` на неактивной — `NotRunning`.
- `status` не возвращает ошибку: снимок всегда доступен, в `Failed` содержит `last_error`.
- `set_transport_mode` делегирует в `TransportRouter::set_mode`; переключение в `Tunnel` без поднятого туннеля — `Transport`.
- `probe_traffic` выполняет один GET по `url` активным транспортом, пишет `TrafficProbe` (с `via`/`observed_peer`) в журнал; в MVP поддерживает `http://` (для `https` оболочка использует локальный прокси ядра).

```rust
let cfg = CoreConfig::default_tunneled("device-1", ":memory:");
let mut session = CoreSession::new(cfg)?;
let snapshot = session.start()?;
println!("{} / {} / {:?}", snapshot.state, snapshot.mode, snapshot.listen_addr);
session.set_transport_mode(TransportMode::Direct)?;
let probe = session.probe_traffic("http://example.com/generate_204")?;
assert!(probe.observed_peer.is_some());
session.stop()?;
```

## 9. FFI: C ABI и JNI

### C ABI (`obereg-ffi`, заголовок `include/obereg.h` пишется вручную, см. ADR [0004](adr/0004-ffi-strategy.md))

```c
typedef struct OberegCore OberegCore;           /* непрозрачный хэндл */

OberegCore* obereg_core_new(const char* config_json);   /* NULL -> ошибка */
void        obereg_core_free(OberegCore* core);
const char* obereg_core_version(void);                  /* "0.1.0" */
int32_t     obereg_core_start(OberegCore* core);
int32_t     obereg_core_stop(OberegCore* core);
const char* obereg_core_status(OberegCore* core);       /* JSON StateSnapshot */
int32_t     obereg_core_set_transport_mode(OberegCore* core, const char* mode); /* "direct"|"tunnel" */
const char* obereg_core_journal_read(OberegCore* core); /* JSON-массив JournalEvent */
const char* obereg_core_last_error(void);               /* текст последней ошибки (thread-local) */
void        obereg_string_free(char* s);                /* единственный способ освободить строки */
```

Коды возврата (варианты `CoreError` отдельными кодами **не** транслируются):

| Код | Значение |
|---|---|
| `0` | успех |
| `-1` | null-хэндл или неверный аргумент |
| `-2` | ошибка ядра (точный вариант — в тексте `obereg_core_last_error`) |

Правила владения памятью:
- Возвращённые `const char*` выделены аллокатором Rust; освобождаются **только** `obereg_string_free`; копировать в строку платформы немедленно.
- Перед каждым вызовом — null-check хэндла; `null` даёт код `-1` без разыменования.
- После `obereg_core_free` хэндл недействителен; двойное освобождение — дефект вызывающей стороны.

### JNI (`obereg-jni`, класс `app.obereg.core.CoreBridge`)

| Метод | Сигнатура (Kotlin) | Поверх |
|---|---|---|
| `create` | `fun create(configJson: String): Long` | `obereg_core_new` |
| `version` | `fun version(): String` | `obereg_core_version` |
| `start` | `fun start(handle: Long): Boolean` | `obereg_core_start` |
| `stop` | `fun stop(handle: Long): Boolean` | `obereg_core_stop` |
| `statusJson` | `fun statusJson(handle: Long): String` | `obereg_core_status` |
| `setTransportMode` | `fun setTransportMode(handle: Long, mode: String): Boolean` | `obereg_core_set_transport_mode` |
| `journalJson` | `fun journalJson(handle: Long): String` | `obereg_core_journal_read` |
| `free` | `fun free(handle: Long)` | `obereg_core_free` |

- `false` у булевых методов означает ошибку; текст — через `obereg_core_last_error` (в Android-лог).

### Пример вызова из Kotlin (Android)

```kotlin
val cfg = """{"config_version":1,"device_id":"device-1","journal_path":"/data/data/app/journal.jsonl"}"""
val handle = CoreBridge.create(cfg)
require(handle != 0L) { "core create failed" }
CoreBridge.start(handle)
val status = CoreBridge.statusJson(handle)            // JSON StateSnapshot
val ok = CoreBridge.setTransportMode(handle, "tunnel")
val journal = CoreBridge.journalJson(handle)          // JSON-массив, парсит оболочка
CoreBridge.stop(handle)
CoreBridge.free(handle)
```

### Пример вызова из Swift (iOS, через C ABI и module map)

```swift
// Для файлового журнала укажите путь в песочнице; ":memory:" — только для тестов.
let cfg = "{\"config_version\":1,\"device_id\":\"ios-1\",\"journal_path\":\":memory:\"}"

guard let core = obereg_core_new(cfg) else { fatalError("obereg_core_new failed") }
_ = obereg_core_start(core)
if let raw = obereg_core_status(core) {
    let json = String(cString: raw)                       // копия во владение Swift
    obereg_string_free(UnsafeMutablePointer(mutating: raw))
    print(json)
}
_ = obereg_core_stop(core)
obereg_core_free(core)
```

## 10. Политика версионирования и ломающих изменений

Правило: SEMVER по `CORE_API_MAJOR.MINOR.PATCH`; при `MAJOR = 0` ломающее изменение увеличивает `MINOR` (`0.1.0 → 0.2.0`), не ломающее — `PATCH`. Каждое изменение фиксируется в `changelog.md`.

| Изменение | Ломающее? | Версия |
|---|---|---|
| Смена сигнатуры (параметры, возврат, имя) публичного элемента | да | MINOR |
| Смена семантики (значения/порядок/гарантии) без смены сигнатуры | да | MINOR |
| Удаление или переименование публичного элемента | да | MINOR |
| Добавление варианта в `ConnectionState` / `JournalKind` / `UpstreamKind` | да (для FFI: match-и, маппинг JSON-строк) | MINOR |
| Добавление варианта в `CoreError` | да | MINOR |
| Добавление обязательного (без `#[serde(default)]`) поля в конфиг | да | MINOR |
| Добавление опционального поля (`#[serde(default)]`) | нет | PATCH |
| Новая функция в трейте (`Journal`, `Transport`, `TunnelEngine`, `StateObserver`) без `default`-реализации | да | MINOR |
| Новая функция трейта с `default`-реализацией (напр. `Journal::location`) | нет | PATCH |
| Новое имя ключа в `JournalEvent::fields` | нет | PATCH |
| Исправление дефекта без изменения контракта | нет | PATCH |

Граничные случаи:
- JSON-значение enum-варианта считается частью контракта: смена/добавление строки (`"httpconnect"` и т. п.) — ломающее даже при неизменной Rust-сигнатуре.
- Любое поле конфига вводится опциональным с `#[serde(default)]`, иначе старые конфиги оболочек перестают парситься.
- Коды возврата C ABI (§9) переиспользованию не подлежат: новые ошибки не меняют смысл `0/-1/-2`.
- `#[deprecated]` ≠ removed: удаление помеченного элемента всё равно ломающее.
- Обратная совместимость FFI в пределах `MAJOR = 0` обязательна (см. ADR [0006](adr/0006-api-versioning.md)).
