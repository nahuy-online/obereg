//! Восходящая часть туннеля: куда именно проксируется трафик.
//!
//! В MVP доступны три варианта: напрямую (сервер не нужен), через внешний SOCKS5
//! и через HTTP CONNECT. Продакшн-узел и обфускация — следующий этап, но интерфейс
//! уже фиксирован.

use std::net::TcpStream;
use std::time::Duration;

use crate::config::{UpstreamConfig, UpstreamKind};
use crate::error::{CoreError, Result};
use crate::transport::resolve_one;
use crate::transport::socks5::socks5_connect;

/// Установить соединение до `target:port` через заданный восходящий узел.
pub(crate) fn connect_upstream(
    upstream: Option<&UpstreamConfig>,
    target: &str,
    port: u16,
    timeout: Duration,
) -> Result<TcpStream> {
    let kind = upstream
        .map(|value| value.kind)
        .unwrap_or(UpstreamKind::Direct);
    match kind {
        UpstreamKind::Direct => connect_tcp(target, port, timeout),
        UpstreamKind::Socks5 => {
            let config = upstream.ok_or_else(|| {
                CoreError::Tunnel("socks5 upstream is selected but not configured".into())
            })?;
            let (host, proxy_port) = split_host_port(&config.addr)?;
            let proxy = connect_tcp(&host, proxy_port, timeout)?;
            proxy.set_read_timeout(Some(timeout))?;
            proxy.set_write_timeout(Some(timeout))?;
            let auth = match (config.username.as_deref(), config.password.as_deref()) {
                (Some(user), Some(pass)) => Some((user, pass)),
                _ => None,
            };
            socks5_connect(&proxy, target, port, auth)?;
            proxy.set_read_timeout(None)?;
            proxy.set_write_timeout(None)?;
            Ok(proxy)
        }
        UpstreamKind::HttpConnect => {
            let config = upstream.ok_or_else(|| {
                CoreError::Tunnel("http_connect upstream is selected but not configured".into())
            })?;
            let (host, proxy_port) = split_host_port(&config.addr)?;
            let mut proxy = connect_tcp(&host, proxy_port, timeout)?;
            proxy.set_read_timeout(Some(timeout))?;
            proxy.set_write_timeout(Some(timeout))?;

            let authorization = match (config.username.as_deref(), config.password.as_deref()) {
                (Some(user), Some(pass)) => format!(
                    "Proxy-Authorization: Basic {}\r\n",
                    base64_encode(format!("{user}:{pass}").as_bytes())
                ),
                _ => String::new(),
            };
            let request = format!(
                "CONNECT {target}:{port} HTTP/1.1\r\nHost: {target}:{port}\r\n{authorization}Proxy-Connection: keep-alive\r\n\r\n"
            );
            use std::io::{Read, Write};
            proxy
                .write_all(request.as_bytes())
                .map_err(|e| CoreError::Tunnel(format!("http_connect: send request: {e}")))?;
            proxy
                .flush()
                .map_err(|e| CoreError::Tunnel(format!("http_connect: flush: {e}")))?;

            let mut buffer = Vec::new();
            let mut chunk = [0_u8; 512];
            loop {
                let read = proxy
                    .read(&mut chunk)
                    .map_err(|e| CoreError::Tunnel(format!("http_connect: read reply: {e}")))?;
                if read == 0 {
                    break;
                }
                buffer.extend_from_slice(&chunk[..read]);
                if buffer.windows(4).any(|window| window == b"\r\n\r\n") || buffer.len() > 8192 {
                    break;
                }
            }
            let head = String::from_utf8_lossy(&buffer).to_string();
            let status = head
                .lines()
                .next()
                .unwrap_or_default()
                .split_whitespace()
                .nth(1)
                .and_then(|value| value.parse::<u16>().ok())
                .unwrap_or(0);
            if status != 200 {
                return Err(CoreError::Tunnel(format!(
                    "http_connect upstream rejected the tunnel with status {status}"
                )));
            }
            proxy.set_read_timeout(None)?;
            proxy.set_write_timeout(None)?;
            Ok(proxy)
        }
    }
}

fn connect_tcp(host: &str, port: u16, timeout: Duration) -> Result<TcpStream> {
    let addr = resolve_one(&format!("{host}:{port}"))?;
    let stream = TcpStream::connect_timeout(&addr, timeout)
        .map_err(|e| CoreError::Tunnel(format!("connect {host}:{port}: {e}")))?;
    let _ = stream.set_nodelay(true);
    Ok(stream)
}

fn split_host_port(addr: &str) -> Result<(String, u16)> {
    let (host, port) = addr
        .rsplit_once(':')
        .ok_or_else(|| CoreError::Tunnel(format!("upstream addr '{addr}' must be host:port")))?;
    if host.is_empty() {
        return Err(CoreError::Tunnel(format!(
            "upstream addr '{addr}' has empty host"
        )));
    }
    let port = port
        .parse::<u16>()
        .map_err(|e| CoreError::Tunnel(format!("invalid upstream port in '{addr}': {e}")))?;
    Ok((host.to_string(), port))
}

/// Минимальный base64 (стандартный алфавит с padding) без внешних зависимостей.
pub(crate) fn base64_encode(input: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut output = String::with_capacity(input.len().div_ceil(3) * 4);
    for chunk in input.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let triple = (b0 << 16) | (b1 << 8) | b2;
        output.push(ALPHABET[((triple >> 18) & 0x3f) as usize] as char);
        output.push(ALPHABET[((triple >> 12) & 0x3f) as usize] as char);
        if chunk.len() > 1 {
            output.push(ALPHABET[((triple >> 6) & 0x3f) as usize] as char);
        } else {
            output.push('=');
        }
        if chunk.len() > 2 {
            output.push(ALPHABET[(triple & 0x3f) as usize] as char);
        } else {
            output.push('=');
        }
    }
    output
}
