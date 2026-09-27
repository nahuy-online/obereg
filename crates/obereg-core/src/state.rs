//! Состояние сессии и наблюдатели.
//!
//! Состояние — это единственный источник правды для оболочек: UI не анализирует
//! внутренние структуры ядра, а читает [`StateSnapshot`] и подписывается на изменения
//! через [`StateObserver`].

use serde::{Deserialize, Serialize};

use crate::config::TransportMode;

/// Состояние соединения.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ConnectionState {
    /// Сессия создана, но не запущена.
    Idle,
    /// Идёт запуск (поднятие туннеля, проверка канала).
    Starting,
    /// Работает штатно.
    Connected,
    /// Работает с ограничениями: туннель не поднялся, сработал откат на `direct`.
    Degraded,
    /// Идёт остановка.
    Stopping,
    /// Ошибка, работа невозможна.
    Failed,
}

impl std::fmt::Display for ConnectionState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let text = match self {
            ConnectionState::Idle => "idle",
            ConnectionState::Starting => "starting",
            ConnectionState::Connected => "connected",
            ConnectionState::Degraded => "degraded",
            ConnectionState::Stopping => "stopping",
            ConnectionState::Failed => "failed",
        };
        f.write_str(text)
    }
}

/// Снимок состояния сессии — то, что видит оболочка.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StateSnapshot {
    /// Текущее состояние.
    pub state: ConnectionState,
    /// Фактически активный режим транспорта.
    pub mode: TransportMode,
    /// Время последнего перехода состояния, мс от эпохи Unix.
    pub since_ms: u64,
    /// Последняя ошибка, если была.
    pub last_error: Option<String>,
    /// Адрес локального прокси ядра, если туннель поднят.
    pub listen_addr: Option<String>,
    /// Байты, отправленные через туннель.
    pub bytes_up: u64,
    /// Байты, полученные через туннель.
    pub bytes_down: u64,
    /// Число обслуженных туннелем соединений.
    pub sessions_served: u64,
}

impl StateSnapshot {
    /// Начальный снимок для заданного режима.
    pub fn initial(mode: TransportMode) -> Self {
        Self {
            state: ConnectionState::Idle,
            mode,
            since_ms: crate::journal::now_ms(),
            last_error: None,
            listen_addr: None,
            bytes_up: 0,
            bytes_down: 0,
            sessions_served: 0,
        }
    }

    /// Сериализация снимка в JSON (контракт биндингов).
    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| "{}".to_string())
    }
}

/// Наблюдатель за изменениями состояния. Вызывается синхронно из потока,
/// который инициировал переход; реализация не должна блокироваться надолго.
pub trait StateObserver: Send + Sync {
    /// Новый снимок состояния.
    fn on_state(&self, snapshot: &StateSnapshot);
}
