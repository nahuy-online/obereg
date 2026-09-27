//! Туннельный движок.
//!
//! Точка расширения продукта: чтобы добавить настоящий протокол обхода (обфускацию,
//! мультиплексирование, выбор входной точки), достаточно реализовать [`TunnelEngine`]
//! и подключить его в сессии. Код выше по стеку не меняется.

pub mod engine;
pub mod upstream;

pub use engine::{LoopbackTunnel, TunnelHandle, TunnelStats};

use std::sync::Arc;

use crate::config::TunnelConfig;
use crate::error::Result;
use crate::journal::Journal;

/// Движок туннеля: поднимает локальную точку входа и проксирует трафик наверх.
pub trait TunnelEngine: Send + Sync {
    /// Имя движка для журнала и реестра модулей.
    fn name(&self) -> &'static str;
    /// Запустить туннель и вернуть управляющий дескриптор.
    fn start(
        &self,
        config: &TunnelConfig,
        journal: Arc<dyn Journal>,
        session_id: &str,
    ) -> Result<TunnelHandle>;
}
