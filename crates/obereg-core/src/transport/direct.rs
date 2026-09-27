//! Прямой транспорт: запросы уходят напрямую через сетевой стек ОС.

use std::net::TcpStream;
use std::time::{Duration, Instant};

use crate::config::TransportMode;
use crate::error::Result;

use super::{resolve_one, Connection, ProbeResult, Transport};

/// Целевой адрес проверки по умолчанию.
pub const DEFAULT_PROBE_TARGET: &str = "1.1.1.1:443";

/// Транспорт `direct`.
#[derive(Debug, Clone)]
pub struct DirectTransport {
    probe_target: String,
    timeout: Duration,
}

impl Default for DirectTransport {
    fn default() -> Self {
        Self::new()
    }
}

impl DirectTransport {
    /// Транспорт с адресом проверки по умолчанию.
    pub fn new() -> Self {
        Self {
            probe_target: DEFAULT_PROBE_TARGET.to_string(),
            timeout: Duration::from_millis(3_000),
        }
    }

    /// Транспорт с заданным адресом проверки (используется тестами и оболочками).
    pub fn with_probe_target(target: impl Into<String>) -> Self {
        Self {
            probe_target: target.into(),
            timeout: Duration::from_millis(3_000),
        }
    }

    /// Задать таймаут установления соединения.
    pub fn with_timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }

    /// Адрес проверки.
    pub fn probe_target(&self) -> &str {
        &self.probe_target
    }
}

impl Transport for DirectTransport {
    fn name(&self) -> &'static str {
        "direct"
    }

    fn mode(&self) -> TransportMode {
        TransportMode::Direct
    }

    fn probe(&self) -> ProbeResult {
        let started = Instant::now();
        let target = self.probe_target.clone();
        let result = (|| -> Result<()> {
            let addr = resolve_one(&target)?;
            let stream = TcpStream::connect_timeout(&addr, self.timeout)?;
            drop(stream);
            Ok(())
        })();
        let elapsed = started.elapsed().as_millis() as u64;
        match result {
            Ok(()) => ProbeResult::ok(elapsed, format!("tcp connect to {}", self.probe_target)),
            Err(err) => ProbeResult::err(elapsed, err.to_string()),
        }
    }

    fn connect(&self, target: &str, port: u16) -> Result<Box<dyn Connection>> {
        let addr = resolve_one(&format!("{target}:{port}"))?;
        let stream = TcpStream::connect_timeout(&addr, self.timeout)?;
        let _ = stream.set_nodelay(true);
        Ok(Box::new(stream))
    }
}
