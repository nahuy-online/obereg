//! Ошибки ядра.
//!
//! Единый тип ошибки для всех модулей ядра. FFI-слой конвертирует его в строку
//! (`obereg_core_last_error`) и не пытается транслировать варианты наружу —
//! стабильность ABI важнее детализации.

use thiserror::Error;

/// Ошибка ядра.
#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum CoreError {
    /// Некорректная или несовместимая конфигурация.
    #[error("config error: {0}")]
    Config(String),
    /// Ошибка ввода-вывода (файлы, сокеты).
    #[error("io error: {0}")]
    Io(String),
    /// Ошибка туннельного движка (bind, accept, upstream).
    #[error("tunnel error: {0}")]
    Tunnel(String),
    /// Ошибка транспортной прослойки (connect, probe, режим недоступен).
    #[error("transport error: {0}")]
    Transport(String),
    /// Ошибка журнала событий.
    #[error("journal error: {0}")]
    Journal(String),
    /// Сессия уже запущена.
    #[error("session is already running")]
    AlreadyRunning,
    /// Сессия не запущена.
    #[error("session is not running")]
    NotRunning,
}

/// Результат операций ядра.
pub type Result<T> = std::result::Result<T, CoreError>;

impl From<std::io::Error> for CoreError {
    fn from(value: std::io::Error) -> Self {
        CoreError::Io(value.to_string())
    }
}

impl From<serde_json::Error> for CoreError {
    fn from(value: serde_json::Error) -> Self {
        CoreError::Config(format!("json: {value}"))
    }
}
