//! Каталог видео: интерфейс источника и локальная реализация.
//!
//! Архитектурный приём тот же, что и с туннелем: сначала фиксируется интерфейс,
//! затем добавляются реализации. Сейчас доступна локальная реализация (список видео
//! в JSON) — она полностью покрыта тестами и не требует сети. Сетевой источник
//! (извлечение потоков у видеосервиса) подключается как ещё один [`CatalogSource`]
//! и не меняет ни ядро, ни оболочки.

use serde::{Deserialize, Serialize};

use crate::error::{CoreError, Result};

/// Один элемент каталога.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VideoItem {
    /// Идентификатор в источнике.
    pub id: String,
    /// Название.
    pub title: String,
    /// Ссылка на воспроизводимый поток.
    pub url: String,
    /// Длительность в секундах, если известна.
    #[serde(default)]
    pub duration_secs: Option<u64>,
    /// Имя источника, из которого пришёл элемент.
    #[serde(default)]
    pub source: String,
}

impl VideoItem {
    /// Проверить, подходит ли элемент под поисковый запрос (регистронезависимо).
    pub fn matches(&self, query: &str) -> bool {
        let needle = query.trim().to_lowercase();
        if needle.is_empty() {
            return true;
        }
        self.title.to_lowercase().contains(&needle) || self.id.to_lowercase().contains(&needle)
    }
}

/// Источник каталога.
///
/// Точка расширения: сетевой источник (поиск и извлечение потоков у видеосервиса)
/// реализует этот же трейт. Оболочки работают только через него.
pub trait CatalogSource: Send + Sync {
    /// Имя источника для журнала и реестра модулей.
    fn name(&self) -> &'static str;
    /// Найти видео по запросу.
    fn search(&self, query: &str, limit: usize) -> Result<Vec<VideoItem>>;
    /// Подборка по умолчанию (главный экран).
    fn featured(&self, limit: usize) -> Result<Vec<VideoItem>>;
}

/// Локальный каталог: список видео, встроенный в приложение.
///
/// Нужен, чтобы интерфейс и плеер можно было разрабатывать и проверять без сети,
/// а также как резервный источник, когда сетевой недоступен.
#[derive(Debug, Clone)]
pub struct LocalCatalogSource {
    items: Vec<VideoItem>,
}

impl LocalCatalogSource {
    /// Создать источник из готового списка.
    pub fn with_items(items: Vec<VideoItem>) -> Self {
        Self { items }
    }

    /// Разобрать каталог из JSON-массива элементов.
    pub fn from_json(json: &str) -> Result<Self> {
        let mut items: Vec<VideoItem> = serde_json::from_str(json)
            .map_err(|e| CoreError::Config(format!("catalog json: {e}")))?;
        for item in items.iter_mut() {
            if item.source.is_empty() {
                item.source = "local".to_string();
            }
        }
        Ok(Self { items })
    }

    /// Число элементов каталога.
    pub fn len(&self) -> usize {
        self.items.len()
    }

    /// Пуст ли каталог.
    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }
}

impl CatalogSource for LocalCatalogSource {
    fn name(&self) -> &'static str {
        "local"
    }

    fn search(&self, query: &str, limit: usize) -> Result<Vec<VideoItem>> {
        Ok(self
            .items
            .iter()
            .filter(|item| item.matches(query))
            .take(limit)
            .cloned()
            .collect())
    }

    fn featured(&self, limit: usize) -> Result<Vec<VideoItem>> {
        Ok(self.items.iter().take(limit).cloned().collect())
    }
}

/// Демонстрационный каталог, встроенный в ядро.
///
/// Использует публичные тестовые видеофайлы: они проверяют воспроизведение и
/// маршрутизацию через туннель, не требуя извлечения потоков у видеосервиса.
pub const DEMO_CATALOG_JSON: &str = r#"[
  {
    "id": "local-sample",
    "title": "Локальное видео (встроено в приложение)",
    "url": "asset:///sample.mp4",
    "duration_secs": 5,
    "source": "demo"
  },
  {
    "id": "sample-5s",
    "title": "Тестовое видео 5 секунд",
    "url": "https://download.samplelib.com/mp4/sample-5s.mp4",
    "duration_secs": 5,
    "source": "demo"
  },
  {
    "id": "sample-10s",
    "title": "Тестовое видео 10 секунд",
    "url": "https://download.samplelib.com/mp4/sample-10s.mp4",
    "duration_secs": 10,
    "source": "demo"
  },
  {
    "id": "filesamples-360",
    "title": "Тестовое видео 640×360 (внешний источник)",
    "url": "https://filesamples.com/samples/video/mp4/sample_640x360.mp4",
    "duration_secs": 13,
    "source": "demo"
  }
]"#;

/// Каталог по умолчанию (демонстрационные данные).
pub fn default_catalog() -> Result<LocalCatalogSource> {
    LocalCatalogSource::from_json(DEMO_CATALOG_JSON)
}
