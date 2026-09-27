//! # obereg-core
//!
//! Платформонезависимое ядро продукта Obereg: транспортная прослойка, туннельный движок,
//! конфигурация, состояние и структурированный журнал событий.
//!
//! ## Границы
//! Ядро не содержит UI-кода и не обращается к платформенным API напрямую. Единственные
//! внешние зависимости — `std`, `serde`, `serde_json`, `thiserror`. Оболочки (Android, iOS,
//! десктоп) подключаются через FFI-слой (`obereg-ffi`, `obereg-jni`).
//!
//! ## Точка входа
//! Основной тип — [`CoreSession`]. Он создаётся из [`CoreConfig`], поднимает туннель,
//! маршрутизирует трафик и ведёт журнал.
//!
//! ```no_run
//! use obereg_core::config::CoreConfig;
//! use obereg_core::session::CoreSession;
//!
//! let cfg = CoreConfig::default_for("device-1", ":memory:");
//! let session = CoreSession::new(cfg).unwrap();
//! let snapshot = session.start().unwrap();
//! println!("{} / {}", snapshot.state, snapshot.mode);
//! ```

pub mod catalog;
pub mod config;
pub mod error;
pub mod journal;
pub mod session;
pub mod state;
pub mod transport;
pub mod tunnel;
pub mod version;

pub use catalog::{CatalogSource, LocalCatalogSource, VideoItem};
pub use config::{CoreConfig, TransportMode};
pub use error::{CoreError, Result};
pub use journal::{Journal, JournalEvent, JournalKind};
pub use session::{CoreSession, TrafficProbe};
pub use state::{ConnectionState, StateSnapshot};
pub use version::{CORE_API_MAJOR, CORE_API_VERSION};
