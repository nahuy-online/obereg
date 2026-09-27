//! Конфигурация ядра.
//!
//! Конфигурация сериализуется в JSON и передаётся оболочками через FFI. Все поля
//! имеют стабильные имена (snake_case), совместимые с контрактом биндингов.

use std::net::SocketAddr;

use serde::{Deserialize, Serialize};

use crate::error::{CoreError, Result};

/// Режим транспортной прослойки.
///
/// * `Direct` — запросы уходят напрямую (нет блокировок либо аварийный режим).
/// * `Tunnel` — запросы уходят через встроенный туннель ядра.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TransportMode {
    /// Напрямую.
    #[default]
    Direct,
    /// Через туннель.
    Tunnel,
}

impl std::fmt::Display for TransportMode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TransportMode::Direct => f.write_str("direct"),
            TransportMode::Tunnel => f.write_str("tunnel"),
        }
    }
}

impl std::str::FromStr for TransportMode {
    type Err = CoreError;

    fn from_str(value: &str) -> Result<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "direct" => Ok(TransportMode::Direct),
            "tunnel" => Ok(TransportMode::Tunnel),
            other => Err(CoreError::Config(format!(
                "unknown transport mode: {other} (expected direct|tunnel)"
            ))),
        }
    }
}

/// Уровень подробности журнала.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LogLevel {
    /// Подробный.
    Trace,
    /// Отладочный.
    Debug,
    /// Информационный (по умолчанию).
    #[default]
    Info,
    /// Предупреждения.
    Warn,
    /// Только ошибки.
    Error,
}

/// Тип восходящего узла туннеля.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum UpstreamKind {
    /// Прямое соединение (MVP, сервер не требуется).
    #[default]
    Direct,
    /// Через внешний SOCKS5-прокси.
    Socks5,
    /// Через HTTP CONNECT-прокси.
    HttpConnect,
}

/// Описание восходящего узла туннеля.
///
/// В рамках MVP серверная часть детализируется только на уровне интерфейса:
/// ядро умеет ходить в `socks5` и `http_connect`, но конкретный продакшн-узел
/// и протокол обфускации — следующий этап.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UpstreamConfig {
    /// Тип узла.
    pub kind: UpstreamKind,
    /// Адрес узла в формате `host:port` (игнорируется для `direct`).
    pub addr: String,
    /// Логин для аутентификации, если требуется.
    #[serde(default)]
    pub username: Option<String>,
    /// Пароль для аутентификации, если требуется.
    #[serde(default)]
    pub password: Option<String>,
}

impl UpstreamConfig {
    /// Прямой upstream без сервера (MVP).
    pub fn direct() -> Self {
        Self {
            kind: UpstreamKind::Direct,
            addr: String::new(),
            username: None,
            password: None,
        }
    }
}

fn default_listen_addr() -> String {
    "127.0.0.1:0".to_string()
}

fn default_http_listen_addr() -> String {
    "127.0.0.1:0".to_string()
}

fn default_handshake_timeout_ms() -> u64 {
    5_000
}

fn default_idle_timeout_ms() -> u64 {
    30_000
}

/// Настройки туннеля.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TunnelConfig {
    /// Адрес, на котором слушает локальный SOCKS5-прокси ядра.
    /// `127.0.0.1:0` означает «выбрать свободный порт автоматически».
    #[serde(default = "default_listen_addr")]
    pub listen_addr: String,
    /// Адрес локального HTTP CONNECT-прокси. Нужен клиентам, которые не умеют SOCKS5
    /// (WebView, браузерные движки, часть HTTP-клиентов). `127.0.0.1:0` — свободный порт.
    #[serde(default = "default_http_listen_addr")]
    pub http_listen_addr: String,
    /// Желаемый режим при старте сессии.
    #[serde(default)]
    pub mode: TransportMode,
    /// Восходящий узел. `null` эквивалентен `kind = "direct"`.
    #[serde(default)]
    pub upstream: Option<UpstreamConfig>,
    /// Таймаут установления соединений, мс.
    #[serde(default = "default_handshake_timeout_ms")]
    pub handshake_timeout_ms: u64,
    /// Таймаут простоя соединения, мс.
    #[serde(default = "default_idle_timeout_ms")]
    pub idle_timeout_ms: u64,
}

impl Default for TunnelConfig {
    fn default() -> Self {
        Self {
            listen_addr: default_listen_addr(),
            http_listen_addr: default_http_listen_addr(),
            mode: TransportMode::Direct,
            upstream: None,
            handshake_timeout_ms: default_handshake_timeout_ms(),
            idle_timeout_ms: default_idle_timeout_ms(),
        }
    }
}

fn default_probe_target() -> String {
    "1.1.1.1:443".to_string()
}

/// Конфигурация ядра целиком.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CoreConfig {
    /// Версия схемы конфигурации.
    pub config_version: u32,
    /// Идентификатор устройства (генерируется оболочкой, попадает в журнал).
    pub device_id: String,
    /// Локаль интерфейса (используется оболочкой, ядро только хранит).
    #[serde(default)]
    pub locale: String,
    /// Уровень подробности журнала.
    #[serde(default)]
    pub log_level: LogLevel,
    /// Путь к файлу журнала. Значение `:memory:` включает журнал в памяти (тесты, бенчмарк).
    pub journal_path: String,
    /// Целевой адрес для проверки доступности прямого канала.
    #[serde(default = "default_probe_target")]
    pub probe_target: String,
    /// Настройки туннеля.
    pub tunnel: TunnelConfig,
}

impl CoreConfig {
    /// Текущая поддерживаемая версия схемы конфигурации.
    pub const CURRENT_CONFIG_VERSION: u32 = 1;

    /// Конфигурация по умолчанию для устройства и пути журнала.
    ///
    /// Режим по умолчанию — `direct`; оболочка включает `tunnel` там, где это нужно
    /// (например, по геолокации или сети).
    pub fn default_for(device_id: impl Into<String>, journal_path: impl Into<String>) -> Self {
        Self {
            config_version: Self::CURRENT_CONFIG_VERSION,
            device_id: device_id.into(),
            locale: "ru".to_string(),
            log_level: LogLevel::Info,
            journal_path: journal_path.into(),
            probe_target: default_probe_target(),
            tunnel: TunnelConfig::default(),
        }
    }

    /// Конфигурация с включённым по умолчанию туннелем (для устройств под блокировкой).
    pub fn default_tunneled(device_id: impl Into<String>, journal_path: impl Into<String>) -> Self {
        let mut cfg = Self::default_for(device_id, journal_path);
        cfg.tunnel.mode = TransportMode::Tunnel;
        cfg
    }

    /// Сериализация в JSON.
    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| "{}".to_string())
    }

    /// Сериализация в JSON с отступами (для логов и подсказок).
    pub fn to_json_pretty(&self) -> String {
        serde_json::to_string_pretty(self).unwrap_or_else(|_| "{}".to_string())
    }

    /// Разбор из JSON с проверкой инвариантов.
    pub fn from_json(json: &str) -> Result<Self> {
        let cfg: CoreConfig = serde_json::from_str(json)?;
        cfg.validate()?;
        Ok(cfg)
    }

    /// Проверка инвариантов конфигурации.
    ///
    /// Правила:
    /// * `config_version` не выше текущей (более новые схемы не поддерживаются);
    /// * `journal_path` не пуст;
    /// * `device_id` не пуст;
    /// * `listen_addr` разбирается как `SocketAddr`;
    /// * при `upstream.kind != direct` адрес узла не пуст и разбирается как `host:port`.
    pub fn validate(&self) -> Result<()> {
        if self.config_version > Self::CURRENT_CONFIG_VERSION {
            return Err(CoreError::Config(format!(
                "config_version {} is newer than supported {}",
                self.config_version,
                Self::CURRENT_CONFIG_VERSION
            )));
        }
        if self.journal_path.trim().is_empty() {
            return Err(CoreError::Config("journal_path must not be empty".into()));
        }
        if self.device_id.trim().is_empty() {
            return Err(CoreError::Config("device_id must not be empty".into()));
        }
        self.tunnel.listen_addr.parse::<SocketAddr>().map_err(|e| {
            CoreError::Config(format!(
                "listen_addr '{}' is not a socket address: {e}",
                self.tunnel.listen_addr
            ))
        })?;
        self.tunnel
            .http_listen_addr
            .parse::<SocketAddr>()
            .map_err(|e| {
                CoreError::Config(format!(
                    "http_listen_addr '{}' is not a socket address: {e}",
                    self.tunnel.http_listen_addr
                ))
            })?;
        if self.tunnel.handshake_timeout_ms == 0 {
            return Err(CoreError::Config("handshake_timeout_ms must be > 0".into()));
        }
        if let Some(up) = &self.tunnel.upstream {
            if up.kind != UpstreamKind::Direct {
                if up.addr.trim().is_empty() {
                    return Err(CoreError::Config(format!(
                        "upstream.addr is required when upstream.kind is {}",
                        match up.kind {
                            UpstreamKind::Socks5 => "socks5",
                            UpstreamKind::HttpConnect => "http_connect",
                            UpstreamKind::Direct => "direct",
                        }
                    )));
                }
                if !up.addr.contains(':') {
                    return Err(CoreError::Config(format!(
                        "upstream.addr '{}' must be host:port",
                        up.addr
                    )));
                }
            }
        }
        Ok(())
    }

    /// Удобный доступ к upstream с подстановкой прямого значения по умолчанию.
    pub fn effective_upstream(&self) -> UpstreamConfig {
        self.tunnel
            .upstream
            .clone()
            .unwrap_or_else(UpstreamConfig::direct)
    }
}
