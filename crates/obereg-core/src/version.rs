//! Версия публичного API ядра.
//!
//! Правила изменения — см. `docs/adr/0006-api-versioning.md` и `CHANGELOG.md`.

/// Мажорная версия публичного API. При `0` любое ломающее изменение поднимает `MINOR`.
pub const CORE_API_MAJOR: u32 = 0;

/// Полная версия публичного API в формате SEMVER.
pub const CORE_API_VERSION: &str = "0.1.0";
