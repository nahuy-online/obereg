//! Маршрутизатор транспорта: выбор режима и автоматический откат.
//!
//! Контракт честности: если трафик фактически ушёл не тем маршрутом, каким его
//! считает сессия, — это дефект. Поэтому при откате роутер переключает активный
//! режим на фактический и увеличивает счётчик отказов, а исходная ошибка туннеля
//! передаётся наружу в [`RoutedConnection::tunnel_error`].

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;

use crate::config::TransportMode;
use crate::error::{CoreError, Result};

use super::{Connection, DirectTransport, ProbeResult, Transport, TunnelTransport};

/// Соединение вместе с фактически использованным маршрутом.
pub struct RoutedConnection {
    /// Открытое соединение.
    pub connection: Box<dyn Connection>,
    /// Режим, которым реально воспользовались.
    pub mode: TransportMode,
    /// Сработал ли автоматический откат с `tunnel` на `direct`.
    pub fell_back: bool,
    /// Причина отката (ошибка туннеля) — нужна для журнала и диагностики.
    pub tunnel_error: Option<String>,
}

/// Выбирает транспорт для запроса и держит активный режим.
///
/// Если активен `tunnel`, туннель недоступен и включён автооткат, роутер уходит
/// в `direct`, **переключает активный режим на `direct`** (чтобы состояние сессии
/// не расходилось с реальностью) и возвращает причину в [`RoutedConnection`].
pub struct TransportRouter {
    direct: DirectTransport,
    tunnel: TunnelTransport,
    active: Mutex<TransportMode>,
    auto_fallback: AtomicBool,
    fallbacks: AtomicU64,
}

impl TransportRouter {
    /// Новый роутер.
    pub fn new(direct: DirectTransport, tunnel: TunnelTransport, initial: TransportMode) -> Self {
        Self {
            direct,
            tunnel,
            active: Mutex::new(initial),
            auto_fallback: AtomicBool::new(true),
            fallbacks: AtomicU64::new(0),
        }
    }

    /// Прямой транспорт.
    pub fn direct(&self) -> &DirectTransport {
        &self.direct
    }

    /// Туннельный транспорт.
    pub fn tunnel(&self) -> &TunnelTransport {
        &self.tunnel
    }

    /// Включён ли автоматический откат.
    pub fn auto_fallback(&self) -> bool {
        self.auto_fallback.load(Ordering::SeqCst)
    }

    /// Включить или выключить автоматический откат.
    pub fn set_auto_fallback(&self, enabled: bool) {
        self.auto_fallback.store(enabled, Ordering::SeqCst);
    }

    /// Число автоматических откатов за время жизни роутера.
    pub fn fallback_count(&self) -> u64 {
        self.fallbacks.load(Ordering::SeqCst)
    }

    /// Активный режим. При отравленном локе возвращается фактически безопасный `direct`.
    pub fn active_mode(&self) -> TransportMode {
        self.active
            .lock()
            .map(|guard| *guard)
            .unwrap_or(TransportMode::Direct)
    }

    /// Переключить активный режим.
    ///
    /// Переключение в `tunnel` требует поднятого туннеля — иначе возвращается ошибка,
    /// и активный режим не меняется.
    pub fn set_mode(&self, mode: TransportMode) -> Result<()> {
        if mode == TransportMode::Tunnel && self.tunnel.proxy_addr().is_none() {
            return Err(CoreError::Transport(
                "tunnel mode requires a running tunnel".into(),
            ));
        }
        let mut guard = self
            .active
            .lock()
            .map_err(|_| CoreError::Transport("transport router lock poisoned".into()))?;
        *guard = mode;
        Ok(())
    }

    /// Проверить доступность активного транспорта.
    pub fn probe_active(&self) -> ProbeResult {
        match self.active_mode() {
            TransportMode::Direct => self.direct.probe(),
            TransportMode::Tunnel => self.tunnel.probe(),
        }
    }

    /// Открыть соединение через активный транспорт с учётом автоотката.
    pub fn connect(&self, target: &str, port: u16) -> Result<RoutedConnection> {
        match self.active_mode() {
            TransportMode::Direct => Ok(RoutedConnection {
                connection: self.direct.connect(target, port)?,
                mode: TransportMode::Direct,
                fell_back: false,
                tunnel_error: None,
            }),
            TransportMode::Tunnel => match self.tunnel.connect(target, port) {
                Ok(connection) => Ok(RoutedConnection {
                    connection,
                    mode: TransportMode::Tunnel,
                    fell_back: false,
                    tunnel_error: None,
                }),
                Err(tunnel_error) => {
                    if !self.auto_fallback() {
                        return Err(tunnel_error);
                    }
                    let connection = self.direct.connect(target, port)?;
                    self.fallbacks.fetch_add(1, Ordering::SeqCst);
                    // Активный режим приводим к фактическому: сессия не должна
                    // сообщать `tunnel`, когда трафик идёт напрямую.
                    let _ = self.set_mode(TransportMode::Direct);
                    Ok(RoutedConnection {
                        connection,
                        mode: TransportMode::Direct,
                        fell_back: true,
                        tunnel_error: Some(tunnel_error.to_string()),
                    })
                }
            },
        }
    }
}
