//! Минимальный HTTP-клиент для проверки прохождения трафика.
//!
//! Ядро сознательно не тянет сетевые зависимости: для пробы достаточно HTTP/1.1 GET
//! без TLS. HTTPS-запросы выполняет оболочка — через локальный SOCKS5-прокси ядра.

use std::time::Duration;

use crate::error::{CoreError, Result};

use super::Connection;

/// Разобранный HTTP-адрес.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedUrl {
    /// Хост (IP или домен).
    pub host: String,
    /// Порт (80 по умолчанию).
    pub port: u16,
    /// Путь с ведущим `/`.
    pub path: String,
}

/// Разобрать `http://host[:port]/path`.
///
/// Поддерживается только схема `http` — это осознанное ограничение MVP.
pub fn parse_http_url(url: &str) -> Result<ParsedUrl> {
    let rest = url.strip_prefix("http://").ok_or_else(|| {
        CoreError::Config(
            "core probe supports only http:// urls; https is performed by the shell through the core SOCKS5 proxy"
                .into(),
        )
    })?;
    let (authority, path) = match rest.find('/') {
        Some(index) => (&rest[..index], &rest[index..]),
        None => (rest, "/"),
    };
    let (host, port) = match authority.rsplit_once(':') {
        Some((host, port)) => (
            host.to_string(),
            port.parse::<u16>()
                .map_err(|e| CoreError::Config(format!("invalid port in url: {e}")))?,
        ),
        None => (authority.to_string(), 80),
    };
    if host.is_empty() {
        return Err(CoreError::Config("url has empty host".into()));
    }
    Ok(ParsedUrl {
        host,
        port,
        path: path.to_string(),
    })
}

/// Выполнить HTTP GET через готовое соединение.
///
/// Возвращает код ответа и тело. Чтение идёт до закрытия соединения
/// (запрос отправляется с `Connection: close`).
pub fn http_get(
    connection: &mut dyn Connection,
    url: &ParsedUrl,
    timeout: Duration,
) -> Result<(u16, Vec<u8>)> {
    connection.set_read_timeout(Some(timeout))?;
    connection.set_write_timeout(Some(timeout))?;

    let host_header = if url.port == 80 {
        url.host.clone()
    } else {
        format!("{}:{}", url.host, url.port)
    };
    let request = format!(
        "GET {} HTTP/1.1\r\nHost: {}\r\nUser-Agent: obereg-core/{}\r\nAccept: */*\r\nConnection: close\r\n\r\n",
        url.path,
        host_header,
        crate::version::CORE_API_VERSION
    );
    connection
        .write_all(request.as_bytes())
        .map_err(|e| CoreError::Transport(format!("send request: {e}")))?;
    connection
        .flush()
        .map_err(|e| CoreError::Transport(format!("flush request: {e}")))?;

    let mut buffer = Vec::new();
    let mut chunk = [0_u8; 8192];
    loop {
        match connection.read(&mut chunk) {
            Ok(0) => break,
            Ok(read) => {
                buffer.extend_from_slice(&chunk[..read]);
                if buffer.len() > 8 * 1024 * 1024 {
                    break;
                }
            }
            Err(err)
                if matches!(
                    err.kind(),
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                ) =>
            {
                break
            }
            Err(err) => {
                if buffer.is_empty() {
                    return Err(CoreError::Transport(format!("read response: {err}")));
                }
                break;
            }
        }
    }

    let split = find_subsequence(&buffer, b"\r\n\r\n").ok_or_else(|| {
        CoreError::Transport("malformed http response: no header terminator".into())
    })?;
    let head = String::from_utf8_lossy(&buffer[..split]).to_string();
    let body = buffer[split + 4..].to_vec();
    let status_line = head.lines().next().unwrap_or_default().to_string();
    let status = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|value| value.parse::<u16>().ok())
        .ok_or_else(|| {
            CoreError::Transport(format!("cannot parse status line: {status_line:?}"))
        })?;

    Ok((status, body))
}

fn find_subsequence(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || haystack.len() < needle.len() {
        return None;
    }
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}
