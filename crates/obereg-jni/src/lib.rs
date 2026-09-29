//! JNI-биндинги ядра Obereg для Android.
//!
//! Соответствуют Kotlin-контракту `app.obereg.core.CoreBridge`:
//!
//! ```kotlin
//! object CoreBridge {
//!     init { System.loadLibrary("obereg_jni") }
//!     external fun version(): String
//!     external fun create(configJson: String): Long
//!     external fun free(handle: Long)
//!     external fun start(handle: Long): String
//!     external fun stop(handle: Long): String
//!     external fun status(handle: Long): String
//!     external fun setTransportMode(handle: Long, mode: String): String
//!     external fun journalJson(handle: Long): String
//! }
//! ```
//!
//! Ошибки возвращаются как JSON-объект `{"error": "..."}`, чтобы оболочка могла
//! показать причину, не падая.
//!
//! Имена экспортируемых функций задаются JNI (`Java_<пакет>_<класс>_<метод>`)
//! и не могут соответствовать snake_case - это требование платформы, а не стиль.
#![allow(non_snake_case)]

use std::sync::Mutex;

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;

use obereg_core::config::{CoreConfig, TransportMode};
use obereg_core::error::CoreError;
use obereg_core::session::CoreSession;

/// Хэндл, которым владеет Kotlin-сторона (указатель в виде `jlong`).
struct JniHandle {
    session: CoreSession,
    last_error: Mutex<Option<String>>,
}

fn rust_string(env: &mut JNIEnv, value: JString) -> Option<String> {
    env.get_string(&value)
        .ok()
        .map(|java_str| java_str.to_string_lossy().into_owned())
}

fn java_string(env: &mut JNIEnv, value: String) -> jstring {
    match env.new_string(value) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn error_json(message: impl Into<String>) -> String {
    serde_json::json!({ "error": message.into() }).to_string()
}

/// Общая обёртка: берёт сессию по хэндлу, выполняет операцию и отдаёт JSON-строку.
fn with_session<F>(env: &mut JNIEnv, handle: jlong, operation: F) -> jstring
where
    F: FnOnce(&CoreSession) -> Result<String, CoreError>,
{
    if handle == 0 {
        return java_string(env, error_json("invalid handle (0)"));
    }
    // Safety: хэндл получен из Java_app_obereg_core_CoreBridge_create и жив до вызова free.
    let handle_ref = unsafe { &*(handle as *const JniHandle) };
    match operation(&handle_ref.session) {
        Ok(value) => java_string(env, value),
        Err(err) => {
            if let Ok(mut guard) = handle_ref.last_error.lock() {
                *guard = Some(err.to_string());
            }
            java_string(env, error_json(err.to_string()))
        }
    }
}

/// `version(): String` - версия публичного API ядра.
#[no_mangle]
pub extern "system" fn Java_app_obereg_core_CoreBridge_version(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    java_string(&mut env, obereg_core::CORE_API_VERSION.to_string())
}

/// `create(configJson: String): Long` - создать сессию ядра. `0` - ошибка.
#[no_mangle]
pub extern "system" fn Java_app_obereg_core_CoreBridge_create(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jlong {
    let Some(json) = rust_string(&mut env, config_json) else {
        return 0;
    };
    match CoreConfig::from_json(&json).and_then(CoreSession::new) {
        Ok(session) => Box::into_raw(Box::new(JniHandle {
            session,
            last_error: Mutex::new(None),
        })) as jlong,
        Err(err) => {
            // Хэндла ещё нет, поэтому сообщение уходит в лог процесса.
            eprintln!("obereg-jni: create failed: {err}");
            0
        }
    }
}

/// `free(handle: Long)` - уничтожить сессию.
#[no_mangle]
pub extern "system" fn Java_app_obereg_core_CoreBridge_free(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        // Safety: хэндл создан в create() и освобождается ровно один раз.
        unsafe {
            drop(Box::from_raw(handle as *mut JniHandle));
        }
    }
}

/// `start(handle: Long): String` - запустить сессию, вернуть JSON `StateSnapshot`.
#[no_mangle]
pub extern "system" fn Java_app_obereg_core_CoreBridge_start(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    with_session(&mut env, handle, |session| {
        session.start().map(|snapshot| snapshot.to_json())
    })
}

/// `stop(handle: Long): String` - остановить сессию.
#[no_mangle]
pub extern "system" fn Java_app_obereg_core_CoreBridge_stop(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    with_session(&mut env, handle, |session| {
        session.stop().map(|snapshot| snapshot.to_json())
    })
}

/// `status(handle: Long): String` - текущее состояние.
#[no_mangle]
pub extern "system" fn Java_app_obereg_core_CoreBridge_status(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    with_session(&mut env, handle, |session| Ok(session.status_json()))
}

/// `setTransportMode(handle: Long, mode: String): String` - переключить режим.
#[no_mangle]
pub extern "system" fn Java_app_obereg_core_CoreBridge_setTransportMode(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    mode: JString,
) -> jstring {
    let Some(mode_text) = rust_string(&mut env, mode) else {
        return java_string(&mut env, error_json("mode is null"));
    };
    with_session(&mut env, handle, move |session| {
        let parsed = mode_text
            .parse::<TransportMode>()
            .map_err(|err| CoreError::Config(err.to_string()))?;
        session
            .set_transport_mode(parsed)
            .map(|snapshot| snapshot.to_json())
    })
}

/// `journalJson(handle: Long): String` - последние события журнала.
#[no_mangle]
pub extern "system" fn Java_app_obereg_core_CoreBridge_journalJson(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    with_session(&mut env, handle, |session| Ok(session.events_json(10_000)))
}

/// `catalogSearch(handle: Long, query: String, limit: Int): String` - поиск в каталоге.#[no_mangle]
pub extern "system" fn Java_app_obereg_core_CoreBridge_catalogSearch(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    query: JString,
    limit: jint,
) -> jstring {
    let Some(query) = rust_string(&mut env, query) else {
        return java_string(&mut env, error_json("query is null"));
    };
    let limit = if limit < 0 { 0 } else { limit as usize };
    with_session(&mut env, handle, move |session| {
        Ok(session.catalog_search_json(&query, limit))
    })
}

/// `parsePlaylists(handle, json): String` — разбор списка плейлистов из раздела.
#[no_mangle]
pub extern "system" fn Java_app_obereg_core_CoreBridge_parsePlaylists(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    playlists_json: JString,
) -> jstring {
    let Some(json) = rust_string(&mut env, playlists_json) else {
        return java_string(&mut env, error_json("playlists json is null"));
    };
    with_session(&mut env, handle, move |_session| {
        let entries = obereg_core::feed::parse_playlists(&json)?;
        serde_json::to_string(&entries)
            .map_err(|error| CoreError::Config(format!("плейлисты: не собраны в JSON: {error}")))
    })
}

/// `localHttpProxyAddr(handle: Long): String` - адрес HTTP CONNECT-прокси ядра/// (пустая строка, если туннель не поднят). Нужен WebView: он умеет только HTTP-прокси.
#[no_mangle]
pub extern "system" fn Java_app_obereg_core_CoreBridge_localHttpProxyAddr(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    with_session(&mut env, handle, |session| {
        Ok(session.local_http_proxy_addr().unwrap_or_default())
    })
}

/// `analyzePlayerResponse(handle: Long, json: String): String` - разбор ответа видеосервиса
/// силами ядра: выбор потока для своего плеера. Логика и её тесты живут в ядре,
/// оболочка отвечает только за сетевой запрос.
#[no_mangle]
pub extern "system" fn Java_app_obereg_core_CoreBridge_analyzePlayerResponse(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    player_response: JString,
) -> jstring {
    let Some(json) = rust_string(&mut env, player_response) else {
        return java_string(&mut env, error_json("player response is null"));
    };
    with_session(&mut env, handle, move |_session| {
        let outcome = obereg_core::extract::analyze_player_response(&json);
        Ok(obereg_core::extract::outcome_to_json(&outcome))
    })
}

/// `analyzeFeed(handle, feedJson, promoId, promoTitle, position): String` - разбор ленты
/// сервиса и (если задан ролик) вставка нашей рекомендации на указанную позицию.
///
/// Возвращает JSON-массив роликов; ошибка - объект с полем `error`, как и везде.
#[no_mangle]
pub extern "system" fn Java_app_obereg_core_CoreBridge_analyzeFeed(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    feed_json: JString,
    promo_id: JString,
    promo_title: JString,
    position: jint,
) -> jstring {
    let Some(json) = rust_string(&mut env, feed_json) else {
        return java_string(&mut env, error_json("feed json is null"));
    };
    let promo_id = rust_string(&mut env, promo_id).unwrap_or_default();
    let promo_title = rust_string(&mut env, promo_title).unwrap_or_default();
    let position = if position < 1 { 3 } else { position as usize };
    with_session(&mut env, handle, move |_session| {
        let items = obereg_core::feed::parse_feed(&json)?;
        let final_items = if promo_id.trim().is_empty() {
            items
        } else {
            let title = promo_title.trim();
            let promo = obereg_core::feed::promo_from(
                &promo_id,
                if title.is_empty() { None } else { Some(title) },
            );
            obereg_core::feed::inject(&items, &promo, position)
        };
        serde_json::to_string(&final_items)
            .map_err(|error| CoreError::Config(format!("лента: не собрана в JSON: {error}")))
    })
}
