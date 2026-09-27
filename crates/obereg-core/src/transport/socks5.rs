//! Клиентская часть SOCKS5.
//!
//! Один и тот же код используют:
//! * [`crate::transport::TunnelTransport`] — чтобы попасть в локальный прокси ядра;
//! * [`crate::tunnel::upstream`] — чтобы попасть во внешний SOCKS5-узел.

use std::io::{Read, Write};
use std::net::{IpAddr, TcpStream};

use crate::error::{CoreError, Result};

const SOCKS5_VERSION: u8 = 0x05;
const CMD_CONNECT: u8 = 0x01;
const METHOD_NO_AUTH: u8 = 0x00;
const METHOD_USERPASS: u8 = 0x02;
const METHOD_NONE_ACCEPTABLE: u8 = 0xFF;
const ATYP_IPV4: u8 = 0x01;
const ATYP_DOMAIN: u8 = 0x03;
const ATYP_IPV6: u8 = 0x04;

/// Выполнить SOCKS5-рукопожатие и `CONNECT target:port` через уже открытое соединение с прокси.
pub(crate) fn socks5_connect(
    stream: &TcpStream,
    target: &str,
    port: u16,
    auth: Option<(&str, &str)>,
) -> Result<()> {
    let mut writer = stream;
    let mut reader = stream;

    // 1. Приветствие: список поддерживаемых методов аутентификации.
    let greeting: &[u8] = if auth.is_some() {
        &[SOCKS5_VERSION, 0x02, METHOD_NO_AUTH, METHOD_USERPASS]
    } else {
        &[SOCKS5_VERSION, 0x01, METHOD_NO_AUTH]
    };
    writer
        .write_all(greeting)
        .map_err(|e| CoreError::Transport(format!("socks5: send greeting: {e}")))?;

    let mut method_reply = [0_u8; 2];
    reader
        .read_exact(&mut method_reply)
        .map_err(|e| CoreError::Transport(format!("socks5: read method reply: {e}")))?;
    if method_reply[0] != SOCKS5_VERSION {
        return Err(CoreError::Transport(format!(
            "socks5: unexpected protocol version {}",
            method_reply[0]
        )));
    }
    match method_reply[1] {
        METHOD_NO_AUTH => {}
        METHOD_USERPASS => {
            let (user, pass) = auth.ok_or_else(|| {
                CoreError::Transport("socks5: proxy requires authentication".into())
            })?;
            if user.len() > 255 || pass.len() > 255 {
                return Err(CoreError::Transport(
                    "socks5: credentials longer than 255 bytes".into(),
                ));
            }
            let mut request = vec![0x01_u8, user.len() as u8];
            request.extend_from_slice(user.as_bytes());
            request.push(pass.len() as u8);
            request.extend_from_slice(pass.as_bytes());
            writer
                .write_all(&request)
                .map_err(|e| CoreError::Transport(format!("socks5: send credentials: {e}")))?;
            let mut auth_reply = [0_u8; 2];
            reader
                .read_exact(&mut auth_reply)
                .map_err(|e| CoreError::Transport(format!("socks5: read auth reply: {e}")))?;
            if auth_reply[1] != 0x00 {
                return Err(CoreError::Transport(
                    "socks5: authentication rejected".into(),
                ));
            }
        }
        METHOD_NONE_ACCEPTABLE => {
            return Err(CoreError::Transport(
                "socks5: no acceptable authentication method".into(),
            ))
        }
        other => {
            return Err(CoreError::Transport(format!(
                "socks5: unsupported authentication method {other}"
            )))
        }
    }

    // 2. Запрос CONNECT.
    let mut request = vec![SOCKS5_VERSION, CMD_CONNECT, 0x00];
    match target.parse::<IpAddr>() {
        Ok(IpAddr::V4(ip)) => {
            request.push(ATYP_IPV4);
            request.extend_from_slice(&ip.octets());
        }
        Ok(IpAddr::V6(ip)) => {
            request.push(ATYP_IPV6);
            request.extend_from_slice(&ip.octets());
        }
        Err(_) => {
            if target.len() > 255 {
                return Err(CoreError::Transport(
                    "socks5: hostname longer than 255 bytes".into(),
                ));
            }
            request.push(ATYP_DOMAIN);
            request.push(target.len() as u8);
            request.extend_from_slice(target.as_bytes());
        }
    }
    request.push((port >> 8) as u8);
    request.push((port & 0xff) as u8);
    writer
        .write_all(&request)
        .map_err(|e| CoreError::Transport(format!("socks5: send connect request: {e}")))?;

    // 3. Ответ на CONNECT.
    let mut header = [0_u8; 4];
    reader
        .read_exact(&mut header)
        .map_err(|e| CoreError::Transport(format!("socks5: read connect reply: {e}")))?;
    if header[0] != SOCKS5_VERSION {
        return Err(CoreError::Transport(format!(
            "socks5: unexpected reply version {}",
            header[0]
        )));
    }
    if header[1] != 0x00 {
        return Err(CoreError::Transport(format!(
            "socks5: connect rejected, reply code {:#04x}",
            header[1]
        )));
    }
    let skip = match header[3] {
        ATYP_IPV4 => 4,
        ATYP_IPV6 => 16,
        ATYP_DOMAIN => {
            let mut len = [0_u8; 1];
            reader
                .read_exact(&mut len)
                .map_err(|e| CoreError::Transport(format!("socks5: read bind address: {e}")))?;
            len[0] as usize
        }
        atyp => {
            return Err(CoreError::Transport(format!(
                "socks5: unknown address type {atyp} in reply"
            )))
        }
    };
    let mut discard = vec![0_u8; skip + 2];
    reader
        .read_exact(&mut discard)
        .map_err(|e| CoreError::Transport(format!("socks5: read bind address/port: {e}")))?;

    Ok(())
}

/// Проверить, что прокси отвечает на SOCKS5-приветствие (лёгкая проверка без CONNECT).
pub(crate) fn socks5_greeting_probe(stream: &TcpStream) -> Result<()> {
    let mut writer = stream;
    let mut reader = stream;
    writer
        .write_all(&[SOCKS5_VERSION, 0x01, METHOD_NO_AUTH])
        .map_err(|e| CoreError::Transport(format!("socks5 probe: send greeting: {e}")))?;
    let mut reply = [0_u8; 2];
    reader
        .read_exact(&mut reply)
        .map_err(|e| CoreError::Transport(format!("socks5 probe: read reply: {e}")))?;
    if reply[0] != SOCKS5_VERSION {
        return Err(CoreError::Transport(format!(
            "socks5 probe: unexpected version {}",
            reply[0]
        )));
    }
    if reply[1] != METHOD_NO_AUTH {
        return Err(CoreError::Transport(format!(
            "socks5 probe: proxy selected method {:#04x} instead of no-auth",
            reply[1]
        )));
    }
    Ok(())
}

/// Разбор заголовка запроса SOCKS5 на стороне сервера.
pub(crate) struct Socks5Request {
    /// Целевой хост (IP или домен).
    pub target: String,
    /// Целевой порт.
    pub port: u16,
}

/// Прочитать запрос SOCKS5 от клиента без аутентификации (локальный прокси приложения).
///
/// Возвращает ошибку, если клиент не поддержал метод без аутентификации
/// (ответ `0xFF` уже отправлен) или если запрос не является `CONNECT`.
pub(crate) fn socks5_read_request(stream: &TcpStream) -> Result<Socks5Request> {
    socks5_read_request_authenticated(stream, None)
}

/// Прочитать запрос SOCKS5 с опциональной проверкой логина и пароля (RFC 1929).
///
/// Нужна внешнему узлу: открытый прокси в интернете — это приглашение для чужих людей
/// гонять через него трафик. Если `expected` задан, клиент обязан пройти USERPASS-проверку.
pub(crate) fn socks5_read_request_authenticated(
    stream: &TcpStream,
    expected: Option<(&str, &str)>,
) -> Result<Socks5Request> {
    let mut writer = stream;
    let mut reader = stream;

    let mut head = [0_u8; 2];
    reader
        .read_exact(&mut head)
        .map_err(|e| CoreError::Transport(format!("socks5 server: read greeting: {e}")))?;
    if head[0] != SOCKS5_VERSION {
        return Err(CoreError::Transport(format!(
            "socks5 server: unsupported version {}",
            head[0]
        )));
    }
    let nmethods = head[1] as usize;
    if nmethods == 0 {
        writer
            .write_all(&[SOCKS5_VERSION, METHOD_NONE_ACCEPTABLE])
            .map_err(|e| CoreError::Transport(format!("socks5 server: write method reply: {e}")))?;
        return Err(CoreError::Transport(
            "socks5 server: client offered no authentication methods".into(),
        ));
    }
    let mut methods = vec![0_u8; nmethods];
    reader
        .read_exact(&mut methods)
        .map_err(|e| CoreError::Transport(format!("socks5 server: read methods: {e}")))?;

    let required = if expected.is_some() {
        METHOD_USERPASS
    } else {
        METHOD_NO_AUTH
    };
    if !methods.contains(&required) {
        writer
            .write_all(&[SOCKS5_VERSION, METHOD_NONE_ACCEPTABLE])
            .map_err(|e| CoreError::Transport(format!("socks5 server: write method reply: {e}")))?;
        return Err(CoreError::Transport(format!(
            "socks5 server: client does not support the required method {required:#04x}"
        )));
    }
    writer
        .write_all(&[SOCKS5_VERSION, required])
        .map_err(|e| CoreError::Transport(format!("socks5 server: write method reply: {e}")))?;

    if let Some((expected_user, expected_pass)) = expected {
        // RFC 1929: [0x01, len(user), user, len(pass), pass] → [0x01, status]
        let mut sub = [0_u8; 2];
        reader
            .read_exact(&mut sub)
            .map_err(|e| CoreError::Transport(format!("socks5 server: read auth header: {e}")))?;
        if sub[0] != 0x01 {
            let _ = writer.write_all(&[0x01, 0x01]);
            return Err(CoreError::Transport(
                "socks5 server: unsupported authentication subnegotiation".into(),
            ));
        }
        let mut user = vec![0_u8; sub[1] as usize];
        reader
            .read_exact(&mut user)
            .map_err(|e| CoreError::Transport(format!("socks5 server: read username: {e}")))?;
        let mut pass_len = [0_u8; 1];
        reader.read_exact(&mut pass_len).map_err(|e| {
            CoreError::Transport(format!("socks5 server: read password length: {e}"))
        })?;
        let mut pass = vec![0_u8; pass_len[0] as usize];
        reader
            .read_exact(&mut pass)
            .map_err(|e| CoreError::Transport(format!("socks5 server: read password: {e}")))?;

        let same_user = user.as_slice() == expected_user.as_bytes();
        let same_pass = pass.as_slice() == expected_pass.as_bytes();
        if !(same_user && same_pass) {
            let _ = writer.write_all(&[0x01, 0x01]);
            return Err(CoreError::Transport(
                "socks5 server: invalid credentials".into(),
            ));
        }
        writer
            .write_all(&[0x01, 0x00])
            .map_err(|e| CoreError::Transport(format!("socks5 server: write auth reply: {e}")))?;
    }

    let mut request_head = [0_u8; 4];
    reader
        .read_exact(&mut request_head)
        .map_err(|e| CoreError::Transport(format!("socks5 server: read request: {e}")))?;
    let (_, cmd, _, atyp) = (
        request_head[0],
        request_head[1],
        request_head[2],
        request_head[3],
    );
    if cmd != CMD_CONNECT {
        // 0x07 = command not supported
        let _ = writer.write_all(&[SOCKS5_VERSION, 0x07, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0]);
        return Err(CoreError::Transport(format!(
            "socks5 server: unsupported command {cmd}"
        )));
    }

    let target = match atyp {
        ATYP_IPV4 => {
            let mut octets = [0_u8; 4];
            reader.read_exact(&mut octets)?;
            std::net::Ipv4Addr::from(octets).to_string()
        }
        ATYP_IPV6 => {
            let mut octets = [0_u8; 16];
            reader.read_exact(&mut octets)?;
            std::net::Ipv6Addr::from(octets).to_string()
        }
        ATYP_DOMAIN => {
            let mut len = [0_u8; 1];
            reader.read_exact(&mut len)?;
            let mut name = vec![0_u8; len[0] as usize];
            reader.read_exact(&mut name)?;
            String::from_utf8_lossy(&name).to_string()
        }
        other => {
            let _ = writer.write_all(&[SOCKS5_VERSION, 0x08, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0]);
            return Err(CoreError::Transport(format!(
                "socks5 server: unknown address type {other}"
            )));
        }
    };
    let mut port_bytes = [0_u8; 2];
    reader.read_exact(&mut port_bytes)?;
    let port = u16::from_be_bytes(port_bytes);

    Ok(Socks5Request { target, port })
}

/// Ответить клиенту успехом на CONNECT.
pub(crate) fn socks5_reply_success(mut stream: &TcpStream) -> Result<()> {
    stream
        .write_all(&[SOCKS5_VERSION, 0x00, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0])
        .map_err(|e| CoreError::Transport(format!("socks5 server: write success reply: {e}")))?;
    Ok(())
}

/// Ответить клиенту ошибкой на CONNECT.
pub(crate) fn socks5_reply_failure(mut stream: &TcpStream, code: u8) -> Result<()> {
    stream
        .write_all(&[SOCKS5_VERSION, code, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0])
        .map_err(|e| CoreError::Transport(format!("socks5 server: write failure reply: {e}")))?;
    Ok(())
}
