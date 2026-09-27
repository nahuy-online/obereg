//! Туннельный движок MVP: локальные прокси на петле.
//!
//! Движок поднимает **два** локальных листенера с одинаковой логикой выхода:
//!
//! * SOCKS5 (`CONNECT`) — для приложений, которые умеют SOCKS (наш плеер, SDK);
//! * HTTP CONNECT — для клиентов, которые умеют только HTTP-прокси (`WebView`
//!   и браузерные движки — именно так видео YouTube идёт через туннель).
//!
//! Оба листенера используют одну абстракцию восходящего узла ([`UpstreamConfig`]):
//! `direct`, `socks5`, `http_connect`. Смена узла не требует правок в оболочках.
//!
//! Свойства реализации, важные при разборе сбоев:
//! * сокет клиента принудительно переводится в блокирующий режим (WinSock наследует
//!   неблокирующий режим слушателя, из-за чего первый же `read` вернул бы `WSAEWOULDBLOCK`);
//! * при завершении потока данных в одном направлении партнёру отправляется half-close,
//!   иначе клиент ждал бы собственного таймаута простоя;
//! * активные клиентские соединения отслеживаются и закрываются при остановке туннеля.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use crate::config::{TunnelAuth, TunnelConfig, UpstreamConfig};
use crate::error::{CoreError, Result};
use crate::journal::{Journal, JournalEvent, JournalKind};
use crate::transport::socks5;

use super::upstream::connect_upstream;
use super::TunnelEngine;

/// Счётчики туннеля.
#[derive(Debug, Default)]
pub struct TunnelStats {
    /// Байты от клиента к цели.
    pub bytes_up: AtomicU64,
    /// Байты от цели к клиенту.
    pub bytes_down: AtomicU64,
    /// Число обслуженных соединений.
    pub sessions_served: AtomicU64,
    /// Число соединений, обслуживаемых прямо сейчас.
    pub active_clients: AtomicU64,
}

impl TunnelStats {
    /// Снимок счётчиков: (вверх, вниз, соединений).
    pub fn snapshot(&self) -> (u64, u64, u64) {
        (
            self.bytes_up.load(Ordering::Relaxed),
            self.bytes_down.load(Ordering::Relaxed),
            self.sessions_served.load(Ordering::Relaxed),
        )
    }

    /// Число активных клиентских соединений.
    pub fn active_clients(&self) -> u64 {
        self.active_clients.load(Ordering::Relaxed)
    }
}

/// Реестр активных клиентских соединений: нужен, чтобы остановка туннеля
/// закрывала их сразу, а не ждала таймаута простоя.
type ClientRegistry = Arc<Mutex<HashMap<u64, TcpStream>>>;

/// Каким протоколом говорит клиент на входе.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum FrontProtocol {
    /// SOCKS5 `CONNECT`.
    Socks5,
    /// HTTP `CONNECT host:port`.
    HttpConnect,
}

/// Чем подтвердить установку соединения клиенту.
#[derive(Debug, Clone, Copy)]
enum Acknowledgement {
    SocksSuccess,
    HttpEstablished,
}

/// Параметры обслуживания одного клиента (собраны в структуру, чтобы не плодить аргументы).
struct ClientContext {
    upstream: Option<UpstreamConfig>,
    auth: Option<TunnelAuth>,
    handshake_timeout: Duration,
    idle_timeout: Duration,
    stats: Arc<TunnelStats>,
    registry: ClientRegistry,
    client_id: u64,
}

/// Дескриптор запущенного туннеля.
pub struct TunnelHandle {
    listen_addr: String,
    http_listen_addr: String,
    stop: Arc<AtomicBool>,
    joins: Vec<JoinHandle<()>>,
    stats: Arc<TunnelStats>,
    clients: ClientRegistry,
}

impl TunnelHandle {
    /// Адрес SOCKS5-прокси.
    pub fn listen_addr(&self) -> &str {
        &self.listen_addr
    }

    /// Адрес HTTP CONNECT-прокси.
    pub fn http_listen_addr(&self) -> &str {
        &self.http_listen_addr
    }

    /// Счётчики трафика.
    pub fn stats(&self) -> Arc<TunnelStats> {
        Arc::clone(&self.stats)
    }

    /// Снимок счётчиков.
    pub fn stats_snapshot(&self) -> (u64, u64, u64) {
        self.stats.snapshot()
    }

    /// Работает ли туннель.
    pub fn is_running(&self) -> bool {
        !self.stop.load(Ordering::SeqCst)
    }

    /// Остановить туннель: закрыть активные клиентские соединения и дождаться потоков приёма.
    pub fn stop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        shutdown_clients(&self.clients);
        for join in self.joins.drain(..) {
            let _ = join.join();
        }
    }
}

impl Drop for TunnelHandle {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        shutdown_clients(&self.clients);
        for join in self.joins.drain(..) {
            let _ = join.join();
        }
    }
}

fn shutdown_clients(clients: &ClientRegistry) {
    if let Ok(mut guard) = clients.lock() {
        for stream in guard.values() {
            let _ = stream.shutdown(Shutdown::Both);
        }
        guard.clear();
    }
}

/// Туннель MVP: локальные SOCKS5 и HTTP CONNECT прокси с абстрактным восходящим узлом.
#[derive(Debug, Default)]
pub struct LoopbackTunnel;

impl LoopbackTunnel {
    /// Создать движок.
    pub fn new() -> Self {
        LoopbackTunnel
    }
}

impl TunnelEngine for LoopbackTunnel {
    fn name(&self) -> &'static str {
        "loopback-socks5+http"
    }

    fn start(
        &self,
        config: &TunnelConfig,
        journal: Arc<dyn Journal>,
        session_id: &str,
    ) -> Result<TunnelHandle> {
        let socks_listener = TcpListener::bind(&config.listen_addr)
            .map_err(|e| CoreError::Tunnel(format!("cannot bind {}: {e}", config.listen_addr)))?;
        let listen_addr = socks_listener
            .local_addr()
            .map_err(|e| CoreError::Tunnel(format!("cannot read listen address: {e}")))?
            .to_string();

        let http_listener = TcpListener::bind(&config.http_listen_addr).map_err(|e| {
            CoreError::Tunnel(format!(
                "cannot bind HTTP proxy at {}: {e}",
                config.http_listen_addr
            ))
        })?;
        let http_listen_addr = http_listener
            .local_addr()
            .map_err(|e| CoreError::Tunnel(format!("cannot read HTTP listen address: {e}")))?
            .to_string();

        let stop = Arc::new(AtomicBool::new(false));
        let stats = Arc::new(TunnelStats::default());
        let clients: ClientRegistry = Arc::new(Mutex::new(HashMap::new()));
        let next_client_id = Arc::new(AtomicU64::new(1));
        let handshake_timeout = Duration::from_millis(config.handshake_timeout_ms.max(1));
        let idle_timeout = Duration::from_millis(config.idle_timeout_ms.max(1));
        let upstream = config.upstream.clone();

        // Журнал — best-effort: сбой записи не должен отменять уже удавшийся bind.
        let _ = journal.append(
            &JournalEvent::new(
                JournalKind::TunnelStart,
                session_id,
                format!(
                    "tunnel engine '{}': socks5 on {listen_addr}, http on {http_listen_addr}",
                    self.name()
                ),
            )
            .with_field("listen_addr", listen_addr.clone())
            .with_field("http_listen_addr", http_listen_addr.clone())
            .with_field("engine", self.name()),
        );

        let joins = vec![
            spawn_accept_loop(
                socks_listener,
                FrontProtocol::Socks5,
                Arc::clone(&journal),
                session_id.to_string(),
                Arc::clone(&stop),
                Arc::clone(&stats),
                Arc::clone(&clients),
                Arc::clone(&next_client_id),
                upstream.clone(),
                config.auth.clone(),
                handshake_timeout,
                idle_timeout,
            )?,
            spawn_accept_loop(
                http_listener,
                FrontProtocol::HttpConnect,
                journal,
                session_id.to_string(),
                Arc::clone(&stop),
                Arc::clone(&stats),
                Arc::clone(&clients),
                next_client_id,
                upstream,
                config.auth.clone(),
                handshake_timeout,
                idle_timeout,
            )?,
        ];

        Ok(TunnelHandle {
            listen_addr,
            http_listen_addr,
            stop,
            joins,
            stats,
            clients,
        })
    }
}

#[allow(clippy::too_many_arguments)]
fn spawn_accept_loop(
    listener: TcpListener,
    protocol: FrontProtocol,
    journal: Arc<dyn Journal>,
    session_id: String,
    stop: Arc<AtomicBool>,
    stats: Arc<TunnelStats>,
    clients: ClientRegistry,
    next_client_id: Arc<AtomicU64>,
    upstream: Option<UpstreamConfig>,
    auth: Option<TunnelAuth>,
    handshake_timeout: Duration,
    idle_timeout: Duration,
) -> Result<JoinHandle<()>> {
    listener
        .set_nonblocking(true)
        .map_err(|e| CoreError::Tunnel(format!("cannot switch listener to non-blocking: {e}")))?;

    Ok(thread::spawn(move || loop {
        if stop.load(Ordering::SeqCst) {
            break;
        }
        match listener.accept() {
            Ok((stream, peer)) => {
                let journal = Arc::clone(&journal);
                let session = session_id.clone();
                let stats = Arc::clone(&stats);
                let clients = Arc::clone(&clients);
                let client_id = next_client_id.fetch_add(1, Ordering::SeqCst);
                let context = ClientContext {
                    upstream: upstream.clone(),
                    auth: auth.clone(),
                    handshake_timeout,
                    idle_timeout,
                    stats: Arc::clone(&stats),
                    registry: Arc::clone(&clients),
                    client_id,
                };
                thread::spawn(move || {
                    stats.active_clients.fetch_add(1, Ordering::Relaxed);
                    if let Err(err) = serve_client(stream, peer, protocol, &context) {
                        let _ = journal.append(&JournalEvent::new(
                            JournalKind::TunnelError,
                            session,
                            format!("client {peer} [{protocol:?}]: {err}"),
                        ));
                    }
                    stats.active_clients.fetch_sub(1, Ordering::Relaxed);
                    if let Ok(mut guard) = clients.lock() {
                        guard.remove(&client_id);
                    }
                });
            }
            Err(ref err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(15));
            }
            Err(err) => {
                let _ = journal.append(&JournalEvent::new(
                    JournalKind::TunnelError,
                    session_id.clone(),
                    format!("accept failed [{protocol:?}]: {err}"),
                ));
                thread::sleep(Duration::from_millis(50));
            }
        }
    }))
}

fn serve_client(
    stream: TcpStream,
    peer: SocketAddr,
    protocol: FrontProtocol,
    context: &ClientContext,
) -> Result<()> {
    // Windows: сокет, принятый из неблокирующего слушателя, наследует неблокирующий режим
    // (WinSock), и все операции чтения сразу возвращают WSAEWOULDBLOCK. Возвращаем режим явно.
    stream.set_nonblocking(false).map_err(|e| {
        CoreError::Tunnel(format!("cannot switch client socket to blocking mode: {e}"))
    })?;
    stream.set_read_timeout(Some(context.handshake_timeout))?;
    stream.set_write_timeout(Some(context.handshake_timeout))?;

    if let Ok(clone) = stream.try_clone() {
        if let Ok(mut guard) = context.registry.lock() {
            guard.insert(context.client_id, clone);
        }
    }

    let (target, port, acknowledgement) = match protocol {
        FrontProtocol::Socks5 => {
            let request = match context.auth.as_ref() {
                Some(auth) => socks5::socks5_read_request_authenticated(
                    &stream,
                    Some((auth.username.as_str(), auth.password.as_str())),
                )?,
                None => socks5::socks5_read_request(&stream)?,
            };
            (request.target, request.port, Acknowledgement::SocksSuccess)
        }
        FrontProtocol::HttpConnect => {
            let credentials = context
                .auth
                .as_ref()
                .map(|auth| (auth.username.as_str(), auth.password.as_str()));
            let (host, port) = read_http_connect(&stream, credentials)?;
            (host, port, Acknowledgement::HttpEstablished)
        }
    };

    let upstream_stream = match connect_upstream(
        context.upstream.as_ref(),
        &target,
        port,
        context.handshake_timeout,
    ) {
        Ok(stream) => stream,
        Err(err) => {
            let _ = acknowledge_failure(&stream, acknowledgement, &err);
            return Err(err);
        }
    };

    acknowledge_success(&stream, acknowledgement)?;
    context
        .stats
        .sessions_served
        .fetch_add(1, Ordering::Relaxed);

    let _ = stream.set_read_timeout(Some(context.idle_timeout));
    let _ = stream.set_write_timeout(Some(context.idle_timeout));
    let _ = upstream_stream.set_read_timeout(Some(context.idle_timeout));
    let _ = upstream_stream.set_write_timeout(Some(context.idle_timeout));

    let upstream_out = upstream_stream
        .try_clone()
        .map_err(|e| CoreError::Tunnel(format!("cannot clone upstream stream: {e}")))?;
    let client_out = stream
        .try_clone()
        .map_err(|e| CoreError::Tunnel(format!("cannot clone client stream: {e}")))?;

    let uplink_stats = Arc::clone(&context.stats);
    let uplink = thread::spawn(move || pump(stream, upstream_stream, uplink_stats, true));
    pump(upstream_out, client_out, Arc::clone(&context.stats), false);
    let _ = uplink.join();

    let _ = peer;
    Ok(())
}

/// Прочитать запрос `CONNECT host:port HTTP/1.1` и вернуть цель.
///
/// Если заданы учётные данные, требуется заголовок `Proxy-Authorization: Basic …`;
/// иначе отвечаем `407` и закрываем соединение — узел не должен быть открытым прокси.
fn read_http_connect(stream: &TcpStream, expected: Option<(&str, &str)>) -> Result<(String, u16)> {
    let mut reader = stream;
    let mut buffer = Vec::new();
    let mut chunk = [0_u8; 512];
    loop {
        let read = reader
            .read(&mut chunk)
            .map_err(|e| CoreError::Tunnel(format!("http proxy: read request: {e}")))?;
        if read == 0 {
            return Err(CoreError::Tunnel(
                "http proxy: client closed before sending request".into(),
            ));
        }
        buffer.extend_from_slice(&chunk[..read]);
        if buffer.windows(4).any(|window| window == b"\r\n\r\n") || buffer.len() > 8192 {
            break;
        }
    }

    let text = String::from_utf8_lossy(&buffer).to_string();
    let request_line = text.lines().next().unwrap_or_default().to_string();
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or_default().to_string();
    let target = parts.next().unwrap_or_default().to_string();

    if let Some((user, pass)) = expected {
        let encoded = super::upstream::base64_encode(format!("{user}:{pass}").as_bytes());
        let provided = text
            .lines()
            .find(|line| {
                line.to_ascii_lowercase()
                    .starts_with("proxy-authorization:")
            })
            .map(|line| {
                line.split_once(':')
                    .map(|(_, value)| value.trim())
                    .unwrap_or_default()
            })
            .map(|value| {
                value
                    .strip_prefix("Basic ")
                    .or_else(|| value.strip_prefix("basic "))
                    .unwrap_or(value)
                    .to_string()
            });
        let ok = provided.as_deref() == Some(encoded.as_str());
        if !ok {
            let mut writer = stream;
            let _ = writer.write_all(
                b"HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"obereg\"\r\nProxy-Agent: obereg\r\n\r\n",
            );
            return Err(CoreError::Tunnel(
                "http proxy: missing or invalid proxy credentials".into(),
            ));
        }
    }

    if !method.eq_ignore_ascii_case("CONNECT") {
        return Err(CoreError::Tunnel(format!(
            "http proxy: unsupported method '{method}' (only CONNECT)"
        )));
    }
    let (host, port_text) = target.rsplit_once(':').ok_or_else(|| {
        CoreError::Tunnel(format!("http proxy: target '{target}' must be host:port"))
    })?;
    let port = port_text
        .parse::<u16>()
        .map_err(|e| CoreError::Tunnel(format!("http proxy: invalid port in '{target}': {e}")))?;
    Ok((host.trim_matches(['[', ']']).to_string(), port))
}

fn acknowledge_success(stream: &TcpStream, acknowledgement: Acknowledgement) -> Result<()> {
    match acknowledgement {
        Acknowledgement::SocksSuccess => socks5::socks5_reply_success(stream),
        Acknowledgement::HttpEstablished => {
            let mut writer = stream;
            writer
                .write_all(b"HTTP/1.1 200 Connection Established\r\nProxy-Agent: obereg\r\n\r\n")
                .map_err(|e| CoreError::Tunnel(format!("http proxy: send 200: {e}")))
        }
    }
}

fn acknowledge_failure(
    stream: &TcpStream,
    acknowledgement: Acknowledgement,
    error: &CoreError,
) -> Result<()> {
    match acknowledgement {
        Acknowledgement::SocksSuccess => {
            socks5::socks5_reply_failure(stream, reply_code_for(error))
        }
        Acknowledgement::HttpEstablished => {
            let mut writer = stream;
            writer
                .write_all(b"HTTP/1.1 502 Bad Gateway\r\nProxy-Agent: obereg\r\n\r\n")
                .map_err(|e| CoreError::Tunnel(format!("http proxy: send 502: {e}")))
        }
    }
}

/// Код ответа SOCKS5 по причине сбоя соединения с целью.
///
/// RFC 1928: `0x04` — сеть/хост недоступны, `0x05` — соединение отклонено.
fn reply_code_for(error: &CoreError) -> u8 {
    let text = error.to_string().to_lowercase();
    if text.contains("refused") {
        0x05
    } else {
        0x04
    }
}

/// Перекачка байтов в одном направлении с подсчётом.
///
/// При завершении потока данных мы закрываем запись на противоположной стороне
/// (`shutdown(Write)`): иначе партнёр не узнаёт о конце ответа и ждёт собственного
/// таймаута простоя — именно это давало задержку в 10 секунд на коротком ответе.
fn pump(mut from: TcpStream, mut to: TcpStream, stats: Arc<TunnelStats>, is_uplink: bool) {
    let mut buffer = [0_u8; 16 * 1024];
    loop {
        match from.read(&mut buffer) {
            Ok(0) => break,
            Ok(read) => {
                if to.write_all(&buffer[..read]).is_err() {
                    break;
                }
                let _ = to.flush();
                if is_uplink {
                    stats.bytes_up.fetch_add(read as u64, Ordering::Relaxed);
                } else {
                    stats.bytes_down.fetch_add(read as u64, Ordering::Relaxed);
                }
            }
            Err(_) => break,
        }
    }
    let _ = to.shutdown(Shutdown::Write);
}
