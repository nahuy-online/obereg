//! Транспортная прослойка.
//!
//! Плеер и любая логика выше этого модуля не знают, как именно доставляется запрос.
//! Они получают [`Connection`] из [`TransportRouter`], а маршрут выбирает прослойка:
//! напрямую (`direct`) или через встроенный туннель (`tunnel`).
//!
//! Правило расширения: новый способ доставки — это новый тип с реализацией [`Transport`],
//! добавленный в [`TransportRouter`]. Никакой код выше менять не требуется.

pub mod direct;
pub mod http;
pub mod router;
pub mod socks5;
pub mod tunnel;

pub use direct::DirectTransport;
pub use router::{RoutedConnection, TransportRouter};
pub use tunnel::TunnelTransport;

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::config::TransportMode;
use crate::error::{CoreError, Result};

/// Открытое соединение, пригодное для чтения и записи.
pub trait Connection: Read + Write + Send {
    /// Адрес удалённого конца соединения.
    ///
    /// Для режима `tunnel` это адрес локального прокси ядра (`127.0.0.1:port`) —
    /// именно это служит доказательством, что трафик пошёл через туннель.
    fn peer_addr(&self) -> Option<SocketAddr>;
    /// Установить таймаут чтения.
    fn set_read_timeout(&self, timeout: Option<Duration>) -> Result<()>;
    /// Установить таймаут записи.
    fn set_write_timeout(&self, timeout: Option<Duration>) -> Result<()>;
    /// Короткое описание соединения для журнала и тестов.
    fn describe(&self) -> String;
}

impl Connection for TcpStream {
    fn peer_addr(&self) -> Option<SocketAddr> {
        TcpStream::peer_addr(self).ok()
    }

    fn set_read_timeout(&self, timeout: Option<Duration>) -> Result<()> {
        TcpStream::set_read_timeout(self, timeout).map_err(Into::into)
    }

    fn set_write_timeout(&self, timeout: Option<Duration>) -> Result<()> {
        TcpStream::set_write_timeout(self, timeout).map_err(Into::into)
    }

    fn describe(&self) -> String {
        match TcpStream::peer_addr(self) {
            Ok(addr) => format!("tcp->{addr}"),
            Err(_) => "tcp".to_string(),
        }
    }
}

/// Результат проверки доступности транспорта.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProbeResult {
    /// Успешна ли проверка.
    pub ok: bool,
    /// Сколько заняла проверка, мс.
    pub elapsed_ms: u64,
    /// Человекочитаемая деталь.
    pub detail: String,
}

impl ProbeResult {
    /// Успешная проверка.
    pub fn ok(elapsed_ms: u64, detail: impl Into<String>) -> Self {
        Self {
            ok: true,
            elapsed_ms,
            detail: detail.into(),
        }
    }

    /// Неуспешная проверка.
    pub fn err(elapsed_ms: u64, detail: impl Into<String>) -> Self {
        Self {
            ok: false,
            elapsed_ms,
            detail: detail.into(),
        }
    }
}

/// Способ доставки сетевого запроса.
pub trait Transport: Send + Sync {
    /// Имя транспорта для журнала.
    fn name(&self) -> &'static str;
    /// Режим, которому соответствует транспорт.
    fn mode(&self) -> TransportMode;
    /// Проверить доступность транспорта (не открывая полезное соединение).
    fn probe(&self) -> ProbeResult;
    /// Открыть соединение до `target:port`.
    fn connect(&self, target: &str, port: u16) -> Result<Box<dyn Connection>>;
}

/// Разрешить `host:port` в один адрес.
pub(crate) fn resolve_one(target: &str) -> Result<SocketAddr> {
    let mut addrs = target
        .to_socket_addrs()
        .map_err(|e| CoreError::Transport(format!("resolve {target}: {e}")))?;
    addrs
        .next()
        .ok_or_else(|| CoreError::Transport(format!("no addresses for {target}")))
}
