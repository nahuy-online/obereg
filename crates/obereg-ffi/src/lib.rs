//! C ABI ядра Obereg.
//!
//! Правила, обязательные для всех потребителей (Swift, десктоп, любой FFI):
//! * хэндл — непрозрачный указатель; `NULL` означает ошибку создания;
//! * строки, возвращаемые функциями `obereg_core_*`, выделяются библиотекой
//!   и освобождаются **только** через [`obereg_string_free`];
//! * строки, возвращаемые `obereg_core_version`, статичны — освобождать не нужно;
//! * функции, возвращающие `char*`, при ошибке возвращают `NULL`, а текст ошибки
//!   доступен через `obereg_core_last_error`.
//!
//! Заголовок для потребителей: `include/obereg.h`.

use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::sync::{Mutex, OnceLock};

use obereg_core::config::{CoreConfig, TransportMode};
use obereg_core::error::CoreError;
use obereg_core::session::CoreSession;

/// Непрозрачный хэндл сессии ядра.
pub struct OberegHandle {
    session: CoreSession,
    last_error: Mutex<Option<String>>,
}

impl OberegHandle {
    fn record_error(&self, message: String) {
        if let Ok(mut guard) = self.last_error.lock() {
            *guard = Some(message);
        }
    }

    fn clear_error(&self) {
        if let Ok(mut guard) = self.last_error.lock() {
            *guard = None;
        }
    }

    fn last_error(&self) -> Option<String> {
        self.last_error.lock().ok().and_then(|guard| guard.clone())
    }
}

static VERSION_CSTRING: OnceLock<CString> = OnceLock::new();

fn version_cstring() -> &'static CString {
    VERSION_CSTRING.get_or_init(|| {
        CString::new(obereg_core::CORE_API_VERSION)
            .unwrap_or_else(|_| CString::new("0.0.0").unwrap())
    })
}

/// Прочитать C-строку. Возвращает `None` для `NULL`.
///
/// # Safety
/// `ptr` должен указывать на валидную NUL-терминированную строку или быть `NULL`.
unsafe fn read_c_string(ptr: *const c_char) -> Option<String> {
    if ptr.is_null() {
        return None;
    }
    Some(CStr::from_ptr(ptr).to_string_lossy().into_owned())
}

/// Выделить строку для передачи наружу. Возвращает `NULL`, если строка содержит NUL.
fn alloc_string(value: String) -> *mut c_char {
    match CString::new(value) {
        Ok(cstring) => cstring.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn respond_json(handle: *mut OberegHandle, result: Result<String, CoreError>) -> *mut c_char {
    match result {
        Ok(value) => alloc_string(value),
        Err(err) => {
            if !handle.is_null() {
                // Safety: вызывающий обязан передать валидный хэндл; проверено на NULL.
                unsafe { (*handle).record_error(err.to_string()) };
            }
            std::ptr::null_mut()
        }
    }
}

/// Версия публичного API ядра (статическая строка, не требует освобождения).
#[no_mangle]
pub extern "C" fn obereg_core_version() -> *const c_char {
    version_cstring().as_ptr()
}

/// JSON конфигурации по умолчанию. Результат освобождать через [`obereg_string_free`].
///
/// # Safety
/// Оба указателя должны быть валидными C-строками или `NULL`.
#[no_mangle]
pub unsafe extern "C" fn obereg_core_default_config_json(
    device_id: *const c_char,
    journal_path: *const c_char,
) -> *mut c_char {
    let (Some(device_id), Some(journal_path)) =
        (read_c_string(device_id), read_c_string(journal_path))
    else {
        return std::ptr::null_mut();
    };
    alloc_string(CoreConfig::default_for(device_id, journal_path).to_json())
}

/// Создать сессию ядра. Возвращает `NULL` при ошибке конфигурации.
///
/// # Safety
/// `config_json` — валидная C-строка с JSON конфигурации.
#[no_mangle]
pub unsafe extern "C" fn obereg_core_new(config_json: *const c_char) -> *mut OberegHandle {
    let Some(json) = read_c_string(config_json) else {
        return std::ptr::null_mut();
    };
    match CoreConfig::from_json(&json).and_then(CoreSession::new) {
        Ok(session) => Box::into_raw(Box::new(OberegHandle {
            session,
            last_error: Mutex::new(None),
        })),
        Err(err) => {
            eprintln!("obereg_core_new failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Уничтожить сессию. Безопасно вызывать с `NULL`.
///
/// # Safety
/// `handle` должен быть получен из [`obereg_core_new`] и ещё не освобождён.
#[no_mangle]
pub unsafe extern "C" fn obereg_core_free(handle: *mut OberegHandle) {
    if !handle.is_null() {
        drop(Box::from_raw(handle));
    }
}

/// Запустить сессию. Возвращает JSON `StateSnapshot` или `NULL` при ошибке.
///
/// # Safety
/// `handle` должен быть валидным хэндлом.
#[no_mangle]
pub unsafe extern "C" fn obereg_core_start(handle: *mut OberegHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let session = &(*handle).session;
    (*handle).clear_error();
    respond_json(handle, session.start().map(|snapshot| snapshot.to_json()))
}

/// Остановить сессию. Возвращает JSON `StateSnapshot` или `NULL` при ошибке.
///
/// # Safety
/// `handle` должен быть валидным хэндлом.
#[no_mangle]
pub unsafe extern "C" fn obereg_core_stop(handle: *mut OberegHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let session = &(*handle).session;
    (*handle).clear_error();
    respond_json(handle, session.stop().map(|snapshot| snapshot.to_json()))
}

/// Текущее состояние. Возвращает JSON `StateSnapshot`.
///
/// # Safety
/// `handle` должен быть валидным хэндлом.
#[no_mangle]
pub unsafe extern "C" fn obereg_core_status(handle: *mut OberegHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let json = (*handle).session.status_json();
    respond_json(handle, Ok(json))
}

/// Переключить режим транспорта (`"direct"` или `"tunnel"`).
///
/// # Safety
/// `handle` — валидный хэндл, `mode` — валидная C-строка.
#[no_mangle]
pub unsafe extern "C" fn obereg_core_set_transport_mode(
    handle: *mut OberegHandle,
    mode: *const c_char,
) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let Some(mode) = read_c_string(mode) else {
        (*handle).record_error("mode is null".to_string());
        return std::ptr::null_mut();
    };
    let session = &(*handle).session;
    (*handle).clear_error();
    respond_json(
        handle,
        mode.parse::<TransportMode>()
            .and_then(|parsed| session.set_transport_mode(parsed))
            .map(|snapshot| snapshot.to_json()),
    )
}

/// Прочитать журнал событий. Возвращает JSON-массив `JournalEvent`.
///
/// # Safety
/// `handle` должен быть валидным хэндлом.
#[no_mangle]
pub unsafe extern "C" fn obereg_core_journal_read(handle: *mut OberegHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let json = (*handle).session.events_json(10_000);
    respond_json(handle, Ok(json))
}

/// Проверка прохождения трафика: HTTP GET через активный транспорт.
/// Возвращает JSON `TrafficProbe`.
///
/// # Safety
/// `handle` — валидный хэндл, `url` — валидная C-строка.
#[no_mangle]
pub unsafe extern "C" fn obereg_core_probe_traffic(
    handle: *mut OberegHandle,
    url: *const c_char,
) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let Some(url) = read_c_string(url) else {
        (*handle).record_error("url is null".to_string());
        return std::ptr::null_mut();
    };
    let session = &(*handle).session;
    (*handle).clear_error();
    respond_json(
        handle,
        session.probe_traffic(&url).map(|probe| probe.to_json()),
    )
}

/// Адрес локального прокси ядра (строка или `NULL`, если туннель не поднят).
///
/// # Safety
/// `handle` должен быть валидным хэндлом.
#[no_mangle]
pub unsafe extern "C" fn obereg_core_local_proxy_addr(handle: *mut OberegHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    match (*handle).session.local_proxy_addr() {
        Some(addr) => alloc_string(addr),
        None => std::ptr::null_mut(),
    }
}

/// Адрес локального HTTP CONNECT-прокси ядра (строка или `NULL`, если туннель не поднят).
///
/// # Safety
/// `handle` должен быть валидным хэндлом.
#[no_mangle]
pub unsafe extern "C" fn obereg_core_local_http_proxy_addr(
    handle: *mut OberegHandle,
) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    match (*handle).session.local_http_proxy_addr() {
        Some(addr) => alloc_string(addr),
        None => std::ptr::null_mut(),
    }
}

/// Поднят ли туннель: 1 — да, 0 — нет.
///
/// # Safety
/// `handle` должен быть валидным хэндлом.
#[no_mangle]
pub unsafe extern "C" fn obereg_core_is_running(handle: *mut OberegHandle) -> i32 {
    if handle.is_null() {
        return 0;
    }
    i32::from((*handle).session.is_running())
}

/// Текст последней ошибки (освобождать через [`obereg_string_free`]) либо `NULL`.
///
/// # Safety
/// `handle` должен быть валидным хэндлом.
#[no_mangle]
pub unsafe extern "C" fn obereg_core_last_error(handle: *mut OberegHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    match (*handle).last_error() {
        Some(message) => alloc_string(message),
        None => std::ptr::null_mut(),
    }
}

/// Поиск в каталоге видео. Возвращает JSON-массив `VideoItem`.
///
/// # Safety
/// `handle` — валидный хэндл, `query` — валидная C-строка.
#[no_mangle]
pub unsafe extern "C" fn obereg_core_catalog_search(
    handle: *mut OberegHandle,
    query: *const c_char,
    limit: u32,
) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let Some(query) = read_c_string(query) else {
        (*handle).record_error("query is null".to_string());
        return std::ptr::null_mut();
    };
    let session = &(*handle).session;
    (*handle).clear_error();
    respond_json(
        handle,
        Ok(session.catalog_search_json(&query, limit as usize)),
    )
}

/// Освободить строку, выделенную библиотекой.
///
/// # Safety
/// `ptr` должен быть результатом одной из функций, возвращающих `char*`
/// (кроме статической `obereg_core_version`), и освобождаться только один раз.
#[no_mangle]
pub unsafe extern "C" fn obereg_string_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        drop(CString::from_raw(ptr));
    }
}
