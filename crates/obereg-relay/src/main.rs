//! Референсный релей туннеля.
//!
//! Задача бинарника — быть внешним узлом, на который клиент направляет трафик
//! (`tunnel.upstream.kind = "socks5"`). Релей поднимает SOCKS5-эндпоинт на публичном
//! адресе и выпускает запросы уже со своей стороны, поэтому клиент в сети с блокировками
//! видит только соединение с релеем.
//!
//! Это референс, а не продакшн-инфраструктура: здесь нет обфускации, ротации входов,
//! лимитов и мониторинга. Что уже есть: рабочая цепочка, журнал, статистика и
//! воспроизводимый запуск — этого достаточно, чтобы поднять первый узел и проверить
//! конечный сценарий.
//!
//! Запуск:
//! ```text
//! obereg-relay --listen 0.0.0.0:1080 --journal /var/log/obereg-relay.jsonl
//! ```
//! Аргументы:
//! * `--listen <addr>`   — адрес прослушивания (по умолчанию `127.0.0.1:1080`);
//! * `--journal <path>`  — файл журнала событий (`:memory:` — без записи на диск);
//! * `--status-file <p>` — куда периодически писать JSON со статусом (адрес, счётчики);
//! * `--print-config`    — напечатать пример конфигурации клиента и выйти.

use std::process::ExitCode;
use std::time::Duration;

use obereg_core::config::{CoreConfig, TransportMode, TunnelAuth, TunnelConfig};
use obereg_core::session::CoreSession;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();

    if args.iter().any(|arg| arg == "--print-config") {
        let listen = flag(&args, "--listen").unwrap_or_else(|| "127.0.0.1:1080".to_string());
        let auth = flag(&args, "--user")
            .zip(flag(&args, "--password"))
            .map(|(username, password)| TunnelAuth { username, password });
        println!("{}", client_config_hint(&listen, auth));
        return ExitCode::SUCCESS;
    }

    match run(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("obereg-relay: {message}");
            ExitCode::FAILURE
        }
    }
}

fn run(args: &[String]) -> Result<(), String> {
    let listen = flag(args, "--listen").unwrap_or_else(|| "127.0.0.1:1080".to_string());
    let journal = flag(args, "--journal").unwrap_or_else(|| ":memory:".to_string());
    let status_file = flag(args, "--status-file");
    let user = flag(args, "--user");
    let password = flag(args, "--password");
    if user.is_some() != password.is_some() {
        return Err("--user и --password задаются вместе".to_string());
    }

    let mut config = CoreConfig::default_tunneled("relay", journal);
    let handshake_timeout_ms = config.tunnel.handshake_timeout_ms;
    let idle_timeout_ms = clamp_idle_minutes(config.tunnel.idle_timeout_ms);
    config.tunnel = TunnelConfig {
        listen_addr: listen.clone(),
        http_listen_addr: "127.0.0.1:0".to_string(),
        mode: TransportMode::Tunnel,
        upstream: None, // релей выпускает трафик напрямую со своей стороны
        auth: user
            .clone()
            .zip(password.clone())
            .map(|(username, password)| TunnelAuth { username, password }),
        handshake_timeout_ms,
        idle_timeout_ms,
    };

    let auth_username = config
        .tunnel
        .auth
        .as_ref()
        .map(|auth| auth.username.clone());
    let session = CoreSession::new(config).map_err(|err| err.to_string())?;
    let snapshot = session.start().map_err(|err| err.to_string())?;

    let socks_addr = session
        .local_proxy_addr()
        .ok_or_else(|| "relay did not start its SOCKS5 endpoint".to_string())?;
    println!("listen: {socks_addr}");
    println!("state: {} / {}", snapshot.state, snapshot.mode);
    println!("client upstream: socks5://{socks_addr}");
    match &auth_username {
        Some(user) => println!("auth: требуется (логин {user})"),
        None => println!(
            "auth: выключена — узел открыт; для публичного адреса задайте --user и --password"
        ),
    }
    if let Some(path) = &session.journal_path() {
        println!("journal: {path}");
    }

    // Периодически публикуем статус: по нему следят тесты и внешний мониторинг.
    loop {
        if let Some(path) = &status_file {
            let status = session.status();
            let payload = serde_json::json!({
                "listen_addr": status.listen_addr,
                "state": status.state.to_string(),
                "mode": status.mode.to_string(),
                "sessions_served": status.sessions_served,
                "bytes_up": status.bytes_up,
                "bytes_down": status.bytes_down,
            });
            let _ = std::fs::write(path, payload.to_string());
        }
        std::thread::sleep(Duration::from_millis(500));
    }
}

/// Держим соединения на релее заметно дольше клиентского простоя: видео смотрят долго.
fn clamp_idle_minutes(client_idle_ms: u64) -> u64 {
    client_idle_ms.saturating_mul(10).max(300_000)
}

fn flag(args: &[String], name: &str) -> Option<String> {
    let index = args.iter().position(|arg| arg == name)?;
    args.get(index + 1).cloned()
}

fn client_config_hint(listen: &str, auth: Option<TunnelAuth>) -> String {
    serde_json::json!({
        "config_version": 1,
        "device_id": "client-device",
        "locale": "ru",
        "log_level": "info",
        "journal_path": "/data/obereg/journal.jsonl",
        "tunnel": {
            "listen_addr": "127.0.0.1:0",
            "http_listen_addr": "127.0.0.1:0",
            "mode": "tunnel",
            "upstream": {
                "kind": "socks5",
                "addr": listen,
                "username": auth.as_ref().map(|a| a.username.clone()),
                "password": auth.as_ref().map(|a| a.password.clone()),
            },
            "handshake_timeout_ms": 5000,
            "idle_timeout_ms": 30000,
        },
    })
    .to_string()
}
