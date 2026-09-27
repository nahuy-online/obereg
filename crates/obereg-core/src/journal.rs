//! Структурированный журнал событий.
//!
//! Журнал нужен, чтобы постфактум разбирать сбои туннеля: каждый запуск, остановка,
//! ошибка, переключение транспорта и проба трафика пишутся отдельной строкой JSON
//! в файл на устройстве. Файл переживает перезапуск процесса: при открытии журнал
//! восстанавливает последний `seq` и продолжает нумерацию.

use std::collections::BTreeMap;
use std::fs::{self, File, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::error::{CoreError, Result};

/// Текущее время в миллисекундах от эпохи Unix.
pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Тип события журнала.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum JournalKind {
    /// Туннель поднят.
    TunnelStart,
    /// Туннель остановлен.
    TunnelStop,
    /// Ошибка туннеля (bind, accept, обработка клиента, upstream).
    TunnelError,
    /// Переключение режима транспорта.
    TransportSwitch,
    /// Сессия запущена.
    SessionStart,
    /// Сессия остановлена.
    SessionStop,
    /// Проба трафика.
    TrafficProbe,
    /// Конфигурация изменена.
    ConfigChanged,
}

/// Событие журнала.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct JournalEvent {
    /// Время события, мс от эпохи Unix.
    pub ts_ms: u64,
    /// Порядковый номер в пределах журнала (назначает журнал при записи).
    pub seq: u64,
    /// Тип события.
    pub kind: JournalKind,
    /// Идентификатор сессии.
    pub session_id: String,
    /// Человекочитаемое описание.
    pub message: String,
    /// Дополнительные поля события.
    #[serde(default)]
    pub fields: BTreeMap<String, String>,
}

impl JournalEvent {
    /// Создать событие без номера (номер назначит журнал).
    pub fn new(
        kind: JournalKind,
        session_id: impl Into<String>,
        message: impl Into<String>,
    ) -> Self {
        Self {
            ts_ms: now_ms(),
            seq: 0,
            kind,
            session_id: session_id.into(),
            message: message.into(),
            fields: BTreeMap::new(),
        }
    }

    /// Добавить дополнительное поле.
    pub fn with_field(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.fields.insert(key.into(), value.into());
        self
    }

    /// Строка JSON для записи в файл (одна строка без перевода строки).
    pub fn to_json_line(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| "{}".to_string())
    }
}

/// Хранилище событий журнала.
pub trait Journal: Send + Sync {
    /// Записать событие, вернуть назначенный `seq`.
    fn append(&self, event: &JournalEvent) -> Result<u64>;
    /// Прочитать все события по порядку.
    fn read_all(&self) -> Result<Vec<JournalEvent>>;
    /// Путь к файлу журнала, если журнал файловый.
    fn location(&self) -> Option<String> {
        None
    }
}

/// Журнал в памяти. Используется в тестах и бенчмарке (нет диска — нет шума в замерах).
#[derive(Debug, Default)]
pub struct MemoryJournal {
    events: Mutex<Vec<JournalEvent>>,
}

impl MemoryJournal {
    /// Пустой журнал в памяти.
    pub fn new() -> Self {
        Self::default()
    }

    /// Число записанных событий.
    pub fn len(&self) -> usize {
        self.events.lock().map(|e| e.len()).unwrap_or(0)
    }

    /// Пуст ли журнал.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

impl Journal for MemoryJournal {
    fn append(&self, event: &JournalEvent) -> Result<u64> {
        let mut guard = self
            .events
            .lock()
            .map_err(|_| CoreError::Journal("memory journal lock poisoned".into()))?;
        let seq = guard.len() as u64 + 1;
        let mut stored = event.clone();
        stored.seq = seq;
        guard.push(stored);
        Ok(seq)
    }

    fn read_all(&self) -> Result<Vec<JournalEvent>> {
        let guard = self
            .events
            .lock()
            .map_err(|_| CoreError::Journal("memory journal lock poisoned".into()))?;
        Ok(guard.clone())
    }
}

/// Файловый журнал в формате JSONL: одно событие — одна строка.
pub struct FileJournal {
    path: PathBuf,
    seq: Mutex<u64>,
    write_lock: Mutex<()>,
}

impl FileJournal {
    /// Открыть (или создать) журнал по пути. Родительские каталоги создаются автоматически.
    /// Последний `seq` восстанавливается из файла, поэтому нумерация непрерывна
    /// между запусками процесса.
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let path = path.as_ref().to_path_buf();
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                fs::create_dir_all(parent).map_err(|e| {
                    CoreError::Journal(format!(
                        "cannot create journal directory {}: {e}",
                        parent.display()
                    ))
                })?;
            }
        }
        let last_seq = Self::last_seq(&path)?;
        Ok(Self {
            path,
            seq: Mutex::new(last_seq),
            write_lock: Mutex::new(()),
        })
    }

    /// Путь к файлу журнала.
    pub fn path(&self) -> &Path {
        &self.path
    }

    fn last_seq(path: &Path) -> Result<u64> {
        if !path.exists() {
            return Ok(0);
        }
        let file = File::open(path).map_err(|e| {
            CoreError::Journal(format!("cannot open journal {}: {e}", path.display()))
        })?;
        let mut last = 0_u64;
        for line in BufReader::new(file).lines() {
            let line = line.map_err(|e| CoreError::Journal(format!("cannot read journal: {e}")))?;
            if line.trim().is_empty() {
                continue;
            }
            if let Ok(event) = serde_json::from_str::<JournalEvent>(&line) {
                if event.seq > last {
                    last = event.seq;
                }
            }
        }
        Ok(last)
    }
}

impl Journal for FileJournal {
    fn append(&self, event: &JournalEvent) -> Result<u64> {
        let _write_guard = self
            .write_lock
            .lock()
            .map_err(|_| CoreError::Journal("journal write lock poisoned".into()))?;
        let mut seq_guard = self
            .seq
            .lock()
            .map_err(|_| CoreError::Journal("journal seq lock poisoned".into()))?;
        *seq_guard += 1;
        let seq = *seq_guard;

        let mut stored = event.clone();
        stored.seq = seq;
        if stored.ts_ms == 0 {
            stored.ts_ms = now_ms();
        }

        let mut file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.path)
            .map_err(|e| CoreError::Journal(format!("cannot open journal for append: {e}")))?;
        file.write_all(stored.to_json_line().as_bytes())
            .and_then(|_| file.write_all(b"\n"))
            .and_then(|_| file.flush())
            .map_err(|e| CoreError::Journal(format!("cannot write journal: {e}")))?;
        // Гарантия сохранности при внезапном завершении процесса.
        let _ = file.sync_data();
        Ok(seq)
    }

    fn read_all(&self) -> Result<Vec<JournalEvent>> {
        if !self.path.exists() {
            return Ok(Vec::new());
        }
        let file = File::open(&self.path)
            .map_err(|e| CoreError::Journal(format!("cannot open journal: {e}")))?;
        let mut events = Vec::new();
        for line in BufReader::new(file).lines() {
            let line = line.map_err(|e| CoreError::Journal(format!("cannot read journal: {e}")))?;
            if line.trim().is_empty() {
                continue;
            }
            // Повреждённая строка не должна ломать чтение всего журнала.
            if let Ok(event) = serde_json::from_str::<JournalEvent>(&line) {
                events.push(event);
            }
        }
        events.sort_by_key(|e| e.seq);
        Ok(events)
    }

    fn location(&self) -> Option<String> {
        Some(self.path.display().to_string())
    }
}
