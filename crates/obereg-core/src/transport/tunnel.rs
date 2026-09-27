//! Транспорт `tunnel`: запросы идут через локальный SOCKS5-прокси ядра.

use std::net::TcpStream;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use crate::config::TransportMode;
use crate::error::{CoreError, Result};

use super::socks5::{socks5_connect, socks5_greeting_probe};
use super::{resolve_one, Connection, ProbeResult, Transport};

/// Транспорт, доставляющий трафик через встроенный туннель.
///
/// Адрес прокси задаётся сессией после успешного запуска туннеля
/// ([`crate::session::CoreSession::start`]) и сбрасывается при остановке.
#[derive(Debug)]
pub struct TunnelTransport {
    proxy_addr: Mutex<Option<String>>,
    timeout: Duration,
}

impl Default for TunnelTransport {
    fn default() -> Self {
        Self::new()
    }
}

impl TunnelTransport {
    /// Транспорт без поднятого туннеля.
    pub fn new() -> Self {
        Self {
            proxy_addr: Mutex::new(None),
            timeout: Duration::from_millis(5_000),
        }
    }

    /// Задать адрес локального прокси (`None` — туннель не поднят).
    pub fn set_proxy_addr(&self, addr: Option<String>) {
        if let Ok(mut guard) = self.proxy_addr.lock() {
            *guard = addr;
        }
    }

    /// Текущий адрес локального прокси.
    pub fn proxy_addr(&self) -> Option<String> {
        self.proxy_addr.lock().ok().and_then(|guard| guard.clone())
    }

    /// Задать таймаут установления соединения.
    pub fn set_timeout(&mut self, timeout: Duration) {
        self.timeout = timeout;
    }
}

impl Transport for TunnelTransport {
    fn name(&self) -> &'static str {
        "tunnel"
    }

    fn mode(&self) -> TransportMode {
        TransportMode::Tunnel
    }

    fn probe(&self) -> ProbeResult {
        let started = Instant::now();
        let addr = match self.proxy_addr() {
            Some(addr) => addr,
            None => {
                return ProbeResult::err(
                    started.elapsed().as_millis() as u64,
                    "tunnel is not running",
                )
            }
        };
        let result = (|| -> Result<()> {
            let resolved = resolve_one(&addr)?;
            let stream = TcpStream::connect_timeout(&resolved, self.timeout)?;
            stream.set_read_timeout(Some(self.timeout))?;
            socks5_greeting_probe(&stream)?;
            Ok(())
        })();
        let elapsed = started.elapsed().as_millis() as u64;
        match result {
            Ok(()) => ProbeResult::ok(elapsed, format!("socks5 greeting ok at {addr}")),
            Err(err) => ProbeResult::err(elapsed, err.to_string()),
        }
    }

    fn connect(&self, target: &str, port: u16) -> Result<Box<dyn Connection>> {
        let addr = self
            .proxy_addr()
            .ok_or_else(|| CoreError::Transport("tunnel is not running".into()))?;
        let resolved = resolve_one(&addr)?;
        let stream = TcpStream::connect_timeout(&resolved, self.timeout)?;
        stream.set_read_timeout(Some(self.timeout))?;
        stream.set_write_timeout(Some(self.timeout))?;
        socks5_connect(&stream, target, port, None)?;
        stream.set_read_timeout(None)?;
        stream.set_write_timeout(None)?;
        let _ = stream.set_nodelay(true);
        Ok(Box::new(stream))
    }
}
