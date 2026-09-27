//! Сессия ядра — единственная точка входа для оболочек.
//!
//! Сессия связывает всё вместе: конфигурацию, транспорт, туннель, состояние и журнал.
//! Оболочка (Android, iOS, десктоп или CLI) не работает с модулями напрямую —
//! только через [`CoreSession`].
//!
//! ## Дисциплина блокировок
//!
//! * Блокировка `handle` удерживается только на время чтения/записи самого дескриптора.
//!   Сетевые операции (`engine.start`, `probe`, `stop`) и уведомление наблюдателей
//!   выполняются **вне** неё — иначе наблюдатель, вызвавший `status()`, получил бы
//!   самоблокировку на нереентерабельном `Mutex`.
//! * `state` и `observers` не захватываются вложенно с другими локами.
//! * Порядок захвата, когда вложенность неизбежна: `handle` → `state` → `observers`.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use crate::catalog::{default_catalog, CatalogSource, VideoItem};
use crate::config::{CoreConfig, TransportMode};
use crate::error::{CoreError, Result};
use crate::journal::{now_ms, FileJournal, Journal, JournalEvent, JournalKind, MemoryJournal};
use crate::state::{ConnectionState, StateObserver, StateSnapshot};
use crate::transport::http::{http_get, parse_http_url};
use crate::transport::{DirectTransport, Transport, TransportRouter, TunnelTransport};
use crate::tunnel::{LoopbackTunnel, TunnelEngine, TunnelHandle};

/// Специальное значение `journal_path`, включающее журнал в памяти (тесты, бенчмарк).
pub const MEMORY_JOURNAL: &str = ":memory:";

/// Результат проверки прохождения трафика.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TrafficProbe {
    /// Проверенный адрес.
    pub url: String,
    /// Маршрут, которым фактически ушёл запрос.
    pub via: TransportMode,
    /// Код HTTP-ответа.
    pub status: u16,
    /// Длина тела ответа в байтах.
    pub body_len: usize,
    /// Длительность запроса, мс.
    pub elapsed_ms: u64,
    /// Адрес удалённого конца соединения.
    /// Для режима `tunnel` это `127.0.0.1:<port>` — доказательство, что запрос ушёл в туннель.
    pub observed_peer: String,
    /// Сработал ли автоматический откат на прямой канал.
    pub fell_back: bool,
}

impl TrafficProbe {
    /// Сериализация в JSON (контракт биндингов).
    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| "{}".to_string())
    }
}

/// Сессия ядра.
pub struct CoreSession {
    config: CoreConfig,
    session_id: String,
    journal: Arc<dyn Journal>,
    router: Arc<TransportRouter>,
    engine: Arc<LoopbackTunnel>,
    catalog: Arc<dyn CatalogSource>,
    handle: Mutex<Option<TunnelHandle>>,
    running: AtomicBool,
    state: Mutex<StateSnapshot>,
    desired_mode: Mutex<TransportMode>,
    observers: Mutex<Vec<Arc<dyn StateObserver>>>,
}

impl CoreSession {
    /// Версия публичного API ядра.
    pub fn version() -> &'static str {
        crate::version::CORE_API_VERSION
    }

    /// Создать сессию из конфигурации.
    ///
    /// Проверяет инварианты конфигурации и открывает журнал. Туннель ещё не поднят —
    /// для этого нужен [`CoreSession::start`].
    pub fn new(config: CoreConfig) -> Result<Self> {
        config.validate()?;

        let journal: Arc<dyn Journal> = if config.journal_path.trim() == MEMORY_JOURNAL
            || config.journal_path.trim().is_empty()
        {
            Arc::new(MemoryJournal::new())
        } else {
            Arc::new(FileJournal::open(&config.journal_path)?)
        };

        let session_id = format!("{}-{}", config.device_id, now_ms());
        let router = Arc::new(TransportRouter::new(
            DirectTransport::with_probe_target(config.probe_target.clone()),
            TunnelTransport::new(),
            TransportMode::Direct,
        ));
        let desired_mode = config.tunnel.mode;
        let catalog: Arc<dyn CatalogSource> = Arc::new(default_catalog()?);

        Ok(Self {
            config,
            session_id,
            journal,
            router,
            engine: Arc::new(LoopbackTunnel::new()),
            catalog,
            handle: Mutex::new(None),
            running: AtomicBool::new(false),
            state: Mutex::new(StateSnapshot::initial(TransportMode::Direct)),
            desired_mode: Mutex::new(desired_mode),
            observers: Mutex::new(Vec::new()),
        })
    }

    /// Конфигурация сессии.
    pub fn config(&self) -> &CoreConfig {
        &self.config
    }

    /// Идентификатор сессии (попадает в журнал).
    pub fn session_id(&self) -> &str {
        &self.session_id
    }

    /// Журнал событий.
    pub fn journal(&self) -> Arc<dyn Journal> {
        Arc::clone(&self.journal)
    }

    /// Путь к файлу журнала, если журнал файловый.
    pub fn journal_path(&self) -> Option<String> {
        self.journal.location()
    }

    /// Подписаться на изменения состояния.
    pub fn add_observer(&self, observer: Arc<dyn StateObserver>) {
        if let Ok(mut guard) = self.observers.lock() {
            guard.push(observer);
        }
    }

    /// Запущена ли сессия.
    ///
    /// Отличается от наличия туннеля: в режиме `direct` сессия запущена,
    /// но туннель не поднят.
    pub fn is_running(&self) -> bool {
        self.running.load(Ordering::SeqCst)
    }

    /// Адрес локального прокси ядра, если туннель поднят.
    pub fn local_proxy_addr(&self) -> Option<String> {
        self.router.tunnel().proxy_addr()
    }

    /// Адрес локального HTTP CONNECT-прокси ядра (нужен WebView и HTTP-клиентам,
    /// которые не умеют SOCKS5).
    pub fn local_http_proxy_addr(&self) -> Option<String> {
        self.handle.lock().ok().and_then(|guard| {
            guard
                .as_ref()
                .map(|handle| handle.http_listen_addr().to_string())
        })
    }

    /// Желаемый режим транспорта (применяется при старте).
    pub fn desired_mode(&self) -> TransportMode {
        self.desired_mode
            .lock()
            .map(|guard| *guard)
            .unwrap_or(self.config.tunnel.mode)
    }

    /// Число автоматических откатов tunnel → direct за время жизни сессии.
    pub fn fallback_count(&self) -> u64 {
        self.router.fallback_count()
    }

    /// Текущее состояние сессии вместе со свежими счётчиками туннеля.
    pub fn status(&self) -> StateSnapshot {
        let mut snapshot = self
            .state
            .lock()
            .map(|guard| guard.clone())
            .unwrap_or_else(|_| StateSnapshot::initial(TransportMode::Direct));
        // Лок `handle` берём отдельно и отпускаем сразу: вложенности с `state` нет.
        let stats = self
            .handle
            .lock()
            .map(|guard| guard.as_ref().map(|handle| handle.stats_snapshot()))
            .unwrap_or(None);
        if let Some((up, down, served)) = stats {
            snapshot.bytes_up = up;
            snapshot.bytes_down = down;
            snapshot.sessions_served = served;
        }
        snapshot.mode = self.router.active_mode();
        snapshot
    }

    /// Снимок состояния в JSON (контракт биндингов).
    pub fn status_json(&self) -> String {
        self.status().to_json()
    }

    /// Запустить сессию: поднять туннель (если выбран режим `tunnel`) или проверить прямой канал.
    pub fn start(&self) -> Result<StateSnapshot> {
        if self.running.swap(true, Ordering::SeqCst) {
            return Err(CoreError::AlreadyRunning);
        }
        if let Err(err) = self.start_inner() {
            self.running.store(false, Ordering::SeqCst);
            self.publish(ConnectionState::Failed, Some(err.to_string()), None);
            return Err(err);
        }
        Ok(self.status())
    }

    fn start_inner(&self) -> Result<()> {
        self.publish(ConnectionState::Starting, None, None);
        let wanted = self.desired_mode();
        self.log(
            JournalKind::SessionStart,
            format!("session started in {wanted} mode"),
            &[("wanted_mode", wanted.to_string())],
        );

        match wanted {
            TransportMode::Tunnel => match self.engine.start(
                &self.config.tunnel,
                Arc::clone(&self.journal),
                &self.session_id,
            ) {
                Ok(handle) => {
                    let listen_addr = handle.listen_addr().to_string();
                    self.store_handle(Some(handle));
                    self.router
                        .tunnel()
                        .set_proxy_addr(Some(listen_addr.clone()));
                    if let Err(err) = self.router.set_mode(TransportMode::Tunnel) {
                        // Режим не включается — сворачиваем туннель, состояние не должно врать.
                        self.take_handle();
                        self.router.tunnel().set_proxy_addr(None);
                        let _ = self.router.set_mode(TransportMode::Direct);
                        self.log(
                            JournalKind::TunnelError,
                            format!("tunnel started but mode switch failed: {err}"),
                            &[("error", err.to_string())],
                        );
                        self.publish(ConnectionState::Degraded, Some(err.to_string()), None);
                        return Ok(());
                    }
                    self.publish(ConnectionState::Connected, None, Some(listen_addr));
                }
                Err(err) => {
                    // Продуктовое решение: туннель не поднялся — сессия продолжает работать
                    // напрямую, состояние Degraded с причиной.
                    self.router.tunnel().set_proxy_addr(None);
                    let _ = self.router.set_mode(TransportMode::Direct);
                    self.log(
                        JournalKind::TunnelError,
                        format!("tunnel start failed: {err}"),
                        &[("error", err.to_string())],
                    );
                    self.log(
                        JournalKind::TransportSwitch,
                        "fallback to direct after tunnel start failure",
                        &[("from", "tunnel".to_string()), ("to", "direct".to_string())],
                    );
                    self.publish(ConnectionState::Degraded, Some(err.to_string()), None);
                }
            },
            TransportMode::Direct => {
                // Сетевая проверка выполняется вне лока `handle`.
                let probe = self.router.direct().probe();
                self.log(
                    JournalKind::TransportSwitch,
                    format!(
                        "direct mode probe: {}",
                        if probe.ok { "ok" } else { "failed" }
                    ),
                    &[
                        ("elapsed_ms", probe.elapsed_ms.to_string()),
                        ("detail", probe.detail.clone()),
                    ],
                );
                if probe.ok {
                    self.publish(ConnectionState::Connected, None, None);
                } else {
                    self.publish(ConnectionState::Degraded, Some(probe.detail), None);
                }
            }
        }
        Ok(())
    }

    /// Остановить сессию и закрыть туннель.
    pub fn stop(&self) -> Result<StateSnapshot> {
        if !self.running.swap(false, Ordering::SeqCst) {
            return Err(CoreError::NotRunning);
        }
        self.publish(ConnectionState::Stopping, None, None);

        // Забираем дескриптор под локом и сразу его отпускаем: остановка туннеля
        // может занимать время (join потоков), держать лок в это время нельзя.
        if let Some(mut handle) = self.take_handle() {
            let listen_addr = handle.listen_addr().to_string();
            handle.stop();
            self.log(
                JournalKind::TunnelStop,
                format!("tunnel stopped ({listen_addr})"),
                &[("listen_addr", listen_addr)],
            );
        }
        self.router.tunnel().set_proxy_addr(None);
        if let Err(err) = self.router.set_mode(TransportMode::Direct) {
            self.log(
                JournalKind::TunnelError,
                format!("cannot reset transport mode to direct: {err}"),
                &[("error", err.to_string())],
            );
        }
        self.log(JournalKind::SessionStop, "session stopped", &[]);
        self.publish(ConnectionState::Idle, None, None);
        Ok(self.status())
    }

    /// Переключить режим транспорта.
    ///
    /// Если сессия запущена — переключение происходит немедленно. Если не запущена —
    /// режим фиксируется и применится при старте.
    pub fn set_transport_mode(&self, mode: TransportMode) -> Result<StateSnapshot> {
        if let Ok(mut guard) = self.desired_mode.lock() {
            *guard = mode;
        }

        if !self.running.load(Ordering::SeqCst) {
            if mode == TransportMode::Direct {
                let _ = self.router.set_mode(TransportMode::Direct);
            }
            self.log(
                JournalKind::ConfigChanged,
                format!("desired transport mode set to {mode}"),
                &[("mode", mode.to_string())],
            );
            return Ok(self.status());
        }

        let previous = self.router.active_mode();
        if previous == mode {
            return Ok(self.status());
        }

        match mode {
            TransportMode::Tunnel => {
                if !self.has_tunnel() {
                    match self.engine.start(
                        &self.config.tunnel,
                        Arc::clone(&self.journal),
                        &self.session_id,
                    ) {
                        Ok(handle) => {
                            let listen_addr = handle.listen_addr().to_string();
                            self.store_handle(Some(handle));
                            self.router.tunnel().set_proxy_addr(Some(listen_addr));
                        }
                        Err(err) => {
                            // Прямой канал продолжает работать: сессия остаётся в работе.
                            self.log(
                                JournalKind::TunnelError,
                                format!("tunnel start on switch failed: {err}"),
                                &[("error", err.to_string())],
                            );
                            self.publish(ConnectionState::Connected, Some(err.to_string()), None);
                            return Err(err);
                        }
                    }
                }
                if let Err(err) = self.router.set_mode(TransportMode::Tunnel) {
                    // Сворачиваем туннель, чтобы состояние не расходилось с реальностью.
                    if let Some(mut handle) = self.take_handle() {
                        handle.stop();
                    }
                    self.router.tunnel().set_proxy_addr(None);
                    self.log(
                        JournalKind::TunnelError,
                        format!("transport switch failed: {err}"),
                        &[("error", err.to_string())],
                    );
                    self.publish(ConnectionState::Connected, Some(err.to_string()), None);
                    return Err(err);
                }
                let listen_addr = self.router.tunnel().proxy_addr();
                self.log(
                    JournalKind::TransportSwitch,
                    format!("transport switched {previous} -> tunnel"),
                    &[("from", previous.to_string()), ("to", "tunnel".to_string())],
                );
                self.publish(ConnectionState::Connected, None, listen_addr);
            }
            TransportMode::Direct => {
                if let Some(mut handle) = self.take_handle() {
                    let listen_addr = handle.listen_addr().to_string();
                    handle.stop();
                    self.router.tunnel().set_proxy_addr(None);
                    self.log(
                        JournalKind::TunnelStop,
                        format!("tunnel stopped on switch to direct ({listen_addr})"),
                        &[("listen_addr", listen_addr)],
                    );
                }
                let _ = self.router.set_mode(TransportMode::Direct);
                self.log(
                    JournalKind::TransportSwitch,
                    format!("transport switched {previous} -> direct"),
                    &[("from", previous.to_string()), ("to", "direct".to_string())],
                );
                self.publish(ConnectionState::Connected, None, None);
            }
        }

        Ok(self.status())
    }

    /// Проверка прохождения трафика: HTTP-запрос через активный транспорт.
    /// Поддерживается только `http://` (см. [`parse_http_url`]). HTTPS выполняет оболочка,
    /// направляя свои соединения на локальный SOCKS5-прокси ядра.
    ///
    /// Если туннель не отвечает и сработал автоматический откат, факт и причина
    /// фиксируются в журнале, а состояние переводится в `Degraded`.
    pub fn probe_traffic(&self, url: &str) -> Result<TrafficProbe> {
        let parsed = parse_http_url(url)?;
        let started = Instant::now();

        let routed = self.router.connect(&parsed.host, parsed.port)?;
        let mut connection = routed.connection;
        let observed_peer = connection
            .peer_addr()
            .map(|addr| addr.to_string())
            .unwrap_or_else(|| "unknown".to_string());
        let via = routed.mode;
        let fell_back = routed.fell_back;
        let tunnel_error = routed.tunnel_error.clone();

        let timeout = Duration::from_millis(self.config.tunnel.handshake_timeout_ms.max(1000) * 2);
        let outcome = http_get(&mut *connection, &parsed, timeout);
        let elapsed_ms = started.elapsed().as_millis() as u64;

        let (status, body_len) = match outcome {
            Ok((status, body)) => (status, body.len()),
            Err(err) => {
                self.log(
                    JournalKind::TunnelError,
                    format!("traffic probe failed: {err}"),
                    &[("url", url.to_string()), ("via", via.to_string())],
                );
                return Err(err);
            }
        };

        if fell_back {
            let detail = tunnel_error.unwrap_or_else(|| "tunnel connect failed".to_string());
            self.log(
                JournalKind::TunnelError,
                format!("tunnel connect failed during probe: {detail}"),
                &[("url", url.to_string()), ("error", detail.clone())],
            );
            self.log(
                JournalKind::TransportSwitch,
                "fallback to direct during probe",
                &[
                    ("from", "tunnel".to_string()),
                    ("to", "direct".to_string()),
                    ("reason", detail.clone()),
                ],
            );
            self.publish(ConnectionState::Degraded, Some(detail), None);
        }

        let probe = TrafficProbe {
            url: url.to_string(),
            via,
            status,
            body_len,
            elapsed_ms,
            observed_peer: observed_peer.clone(),
            fell_back,
        };
        self.log(
            JournalKind::TrafficProbe,
            format!("traffic probe {url} -> {} via {via}", status),
            &[
                ("url", url.to_string()),
                ("via", via.to_string()),
                ("status", status.to_string()),
                ("elapsed_ms", elapsed_ms.to_string()),
                ("observed_peer", observed_peer),
                ("fell_back", fell_back.to_string()),
            ],
        );
        Ok(probe)
    }

    /// Поиск в каталоге видео.
    ///
    /// Источник каталога — точка расширения: сейчас это локальный список,
    /// сетевой источник (извлечение потоков у видеосервиса) реализует тот же трейт
    /// и не требует изменений в оболочках.
    pub fn catalog_search(&self, query: &str, limit: usize) -> Result<Vec<VideoItem>> {
        self.catalog.search(query, limit)
    }

    /// Подборка каталога для главного экрана.
    pub fn catalog_featured(&self, limit: usize) -> Result<Vec<VideoItem>> {
        self.catalog.featured(limit)
    }

    /// Имя источника каталога.
    pub fn catalog_source(&self) -> &'static str {
        self.catalog.name()
    }

    /// Поиск в каталоге в виде JSON-массива (контракт биндингов).
    pub fn catalog_search_json(&self, query: &str, limit: usize) -> String {
        match self.catalog_search(query, limit) {
            Ok(items) => serde_json::to_string(&items).unwrap_or_else(|_| "[]".to_string()),
            Err(err) => serde_json::json!({ "error": err.to_string() }).to_string(),
        }
    }

    /// Последние события журнала (не более `limit`).
    pub fn events(&self, limit: usize) -> Result<Vec<JournalEvent>> {
        let all = self.journal.read_all()?;
        let len = all.len();
        let skip = len.saturating_sub(limit);
        Ok(all.into_iter().skip(skip).collect())
    }

    /// Последние события журнала в виде JSON-массива (контракт биндингов).
    pub fn events_json(&self, limit: usize) -> String {
        match self.events(limit) {
            Ok(events) => serde_json::to_string(&events).unwrap_or_else(|_| "[]".to_string()),
            Err(err) => serde_json::json!({ "error": err.to_string() }).to_string(),
        }
    }

    // --- внутренние помощники ---

    fn has_tunnel(&self) -> bool {
        self.handle
            .lock()
            .map(|guard| guard.is_some())
            .unwrap_or(false)
    }

    fn store_handle(&self, handle: Option<TunnelHandle>) {
        if let Ok(mut guard) = self.handle.lock() {
            *guard = handle;
        }
    }

    fn take_handle(&self) -> Option<TunnelHandle> {
        self.handle.lock().ok().and_then(|mut guard| guard.take())
    }

    fn log(&self, kind: JournalKind, message: impl Into<String>, fields: &[(&str, String)]) {
        let mut event = JournalEvent::new(kind, self.session_id.clone(), message);
        for (key, value) in fields {
            event.fields.insert((*key).to_string(), value.clone());
        }
        // Журнал — best-effort: сбой записи не должен ломать работу сессии.
        let _ = self.journal.append(&event);
    }

    /// Опубликовать состояние.
    ///
    /// Лок `handle` здесь не берётся: наблюдатель может безопасно вызвать
    /// `status()`, `stop()` или `set_transport_mode()`.
    fn publish(
        &self,
        state: ConnectionState,
        last_error: Option<String>,
        listen_addr: Option<String>,
    ) {
        let snapshot = {
            let mut guard = match self.state.lock() {
                Ok(guard) => guard,
                Err(_) => return,
            };
            guard.state = state;
            guard.mode = self.router.active_mode();
            guard.since_ms = now_ms();
            guard.last_error = last_error;
            guard.listen_addr = listen_addr;
            guard.clone()
        };
        let observers: Vec<Arc<dyn StateObserver>> = self
            .observers
            .lock()
            .map(|guard| guard.clone())
            .unwrap_or_default();
        for observer in observers {
            observer.on_state(&snapshot);
        }
    }
}
