//! CLI-харнесс ядра Obereg: проверка ядра на десктопе, сквозная демонстрация
//! прохождения трафика через туннель и бенчмарк инициализации.
//!
//! Команды:
//! * `version` — версии CLI и публичного API ядра;
//! * `config-default [--device ID] [--journal PATH]` — JSON конфигурации по умолчанию;
//! * `status|start|stop --config PATH` — управление сессией по файлу конфигурации;
//! * `probe --config PATH --url http://...` — проверка прохождения трафика;
//! * `journal --config PATH [--limit N]` — события журнала;
//! * `demo` — сквозная демонстрация: локальный HTTP-сервер + туннель + доказательства;
//! * `bench [--iterations N] [--out PATH] [--json]` — замер инициализации ядра.

use std::io::{Read, Write};
use std::net::TcpListener;
use std::process::ExitCode;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Instant;

use obereg_core::config::CoreConfig;
use obereg_core::session::CoreSession;

const DEMO_BODY: &str = "obereg-demo";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match run(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("error: {message}");
            ExitCode::FAILURE
        }
    }
}

fn run(args: &[String]) -> Result<(), String> {
    let command = args.first().map(String::as_str).unwrap_or("help");
    match command {
        "version" => {
            println!(
                "obereg-cli {} | core api {} | {} {}",
                env!("CARGO_PKG_VERSION"),
                CoreSession::version(),
                std::env::consts::OS,
                std::env::consts::ARCH
            );
            Ok(())
        }
        "config-default" => {
            let device = flag(args, "--device").unwrap_or_else(|| "cli-device".to_string());
            let journal = flag(args, "--journal").unwrap_or_else(|| ":memory:".to_string());
            println!(
                "{}",
                CoreConfig::default_for(device, journal).to_json_pretty()
            );
            Ok(())
        }
        "status" => {
            let session = session_from_args(args)?;
            println!("{}", pretty(&session.status())?);
            Ok(())
        }
        "start" => {
            let session = session_from_args(args)?;
            let snapshot = session.start().map_err(to_string)?;
            println!("{}", pretty(&snapshot)?);
            println!("local proxy: {:?}", session.local_proxy_addr());
            Ok(())
        }
        "stop" => {
            let session = session_from_args(args)?;
            let snapshot = session.stop().map_err(to_string)?;
            println!("{}", pretty(&snapshot)?);
            Ok(())
        }
        "probe" => {
            let session = session_from_args(args)?;
            let url = flag(args, "--url").ok_or_else(|| "--url is required".to_string())?;
            session.start().map_err(to_string)?;
            let probe = session.probe_traffic(&url).map_err(to_string)?;
            println!("{}", pretty(&probe)?);
            Ok(())
        }
        "extract-json" => {
            // Живая проверка извлечения без телефона: читаем уже сохранённый ответ
            // сервиса и прогоняем через логику ядра. Так удобно проверять,
            // что отдаёт сервис, если запрос сделан отдельно.
            let path = flag(args, "--file").ok_or_else(|| "--file is required".to_string())?;
            let raw = std::fs::read_to_string(&path)
                .map_err(|error| format!("не читается файл {path}: {error}"))?;
            let outcome = obereg_core::extract::analyze_player_response(&raw);
            println!("{}", obereg_core::extract::outcome_to_json(&outcome));
            Ok(())
        }
        "feed-json" => {
            // Разбор ленты и наша рекомендация: читаем сохранённый ответ раздела,
            // собираем ролики в порядке ленты и (по желанию) вставляем свой на позицию.
            let path = flag(args, "--file").ok_or_else(|| "--file is required".to_string())?;
            let raw = std::fs::read_to_string(&path)
                .map_err(|error| format!("не читается файл {path}: {error}"))?;
            let items = obereg_core::feed::parse_feed(&raw).map_err(to_string)?;
            let position: usize =
                flag(args, "--position").and_then(|v| v.parse().ok()).unwrap_or(3);
            let final_items = match flag(args, "--promo") {
                Some(video_id) => {
                    let title = flag(args, "--promo-title");
                    let promo = obereg_core::feed::promo_from(&video_id, title.as_deref());
                    obereg_core::feed::inject(&items, &promo, position)
                }
                None => items,
            };
            println!("роликов в ленте: {}", final_items.len());
            for (index, item) in final_items.iter().enumerate() {
                println!(
                    "{:>2}. {}{} [{}]{}",
                    index + 1,
                    if item.injected { "★ " } else { "" },
                    if item.title.is_empty() { "(без названия)" } else { &item.title },
                    item.video_id,
                    if item.channel.is_empty() {
                        String::new()
                    } else {
                        format!(" — {}", item.channel)
                    }
                );
            }
            Ok(())
        }
        "journal" => {
            let session = session_from_args(args)?;
            let limit: usize = flag(args, "--limit")
                .and_then(|value| value.parse().ok())
                .unwrap_or(50);
            for event in session.events(limit).map_err(to_string)? {
                println!(
                    "[{:>4}] {:<32} {:<20} {}",
                    event.seq,
                    format!("{}", event.ts_ms),
                    format!("{:?}", event.kind),
                    event.message
                );
            }
            Ok(())
        }
        "demo" => cmd_demo(),
        "bench" => cmd_bench(args),
        "help" | "--help" | "-h" => {
            print_help();
            Ok(())
        }
        other => Err(format!("unknown command: {other} (try `obereg-cli help`)")),
    }
}

fn print_help() {
    println!("obereg-cli — харнесс ядра Obereg");
    println!();
    println!("  version");
    println!("  config-default [--device ID] [--journal PATH]");
    println!("  status|start|stop --config PATH");
    println!("  probe --config PATH --url http://host:port/path");
    println!("  journal --config PATH [--limit N]");
    println!("  demo");
    println!("  bench [--iterations N] [--out PATH] [--json]");
}

fn session_from_args(args: &[String]) -> Result<CoreSession, String> {
    let path = flag(args, "--config").ok_or_else(|| "--config is required".to_string())?;
    let json = std::fs::read_to_string(&path).map_err(|err| format!("read {path}: {err}"))?;
    let config = CoreConfig::from_json(&json).map_err(to_string)?;
    CoreSession::new(config).map_err(to_string)
}

fn flag(args: &[String], name: &str) -> Option<String> {
    let index = args.iter().position(|arg| arg == name)?;
    args.get(index + 1).cloned()
}

fn to_string(value: impl std::fmt::Display) -> String {
    value.to_string()
}

fn pretty<T: serde::Serialize>(value: &T) -> Result<String, String> {
    serde_json::to_string_pretty(value).map_err(to_string)
}

/// Сквозная демонстрация: локальный HTTP-сервер, туннель и доказательства маршрута.
fn cmd_demo() -> Result<(), String> {
    println!("== Obereg demo: прохождение трафика через встроенный туннель ==");

    // 1. Локальный HTTP-сервер, который помнит, с какого адреса пришёл запрос.
    let listener = TcpListener::bind("127.0.0.1:0").map_err(to_string)?;
    let server_addr = listener.local_addr().map_err(to_string)?.to_string();
    let server_peer: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
    let peer_sink = Arc::clone(&server_peer);
    thread::spawn(move || {
        let Ok((mut stream, _)) = listener.accept() else {
            return;
        };
        if let Ok(remote) = stream.peer_addr() {
            *peer_sink.lock().unwrap() = Some(remote.to_string());
        }
        let mut buffer = [0_u8; 2048];
        let _ = stream.read(&mut buffer);
        let response = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
            DEMO_BODY.len(),
            DEMO_BODY
        );
        let _ = stream.write_all(response.as_bytes());
        let _ = stream.flush();
    });
    println!("локальный сервер: http://{server_addr}/");

    // 2. Сессия в режиме туннеля с файловым журналом.
    let journal_path = std::env::temp_dir().join("obereg-demo-journal.jsonl");
    let _ = std::fs::remove_file(&journal_path);
    let config =
        CoreConfig::default_tunneled("cli-demo", journal_path.to_string_lossy().to_string());
    let session = CoreSession::new(config).map_err(to_string)?;

    let snapshot = session.start().map_err(to_string)?;
    println!(
        "сессия: state={} mode={} listen_addr={:?}",
        snapshot.state, snapshot.mode, snapshot.listen_addr
    );

    // 3. Проба трафика: запрос уходит через локальный прокси ядра.
    let probe = session
        .probe_traffic(&format!("http://{server_addr}/"))
        .map_err(to_string)?;
    println!(
        "проба: via={} status={} body_len={} elapsed_ms={}",
        probe.via, probe.status, probe.body_len, probe.elapsed_ms
    );
    println!("наблюдаемый peer ядра: {}", probe.observed_peer);
    let server_seen = server_peer.lock().unwrap().clone();
    println!("сервер видел адрес: {:?}", server_seen);

    let through_tunnel = probe.via == obereg_core::config::TransportMode::Tunnel
        && probe.observed_peer.starts_with("127.0.0.1:")
        && server_seen.as_deref() != Some(probe.observed_peer.as_str());
    println!(
        "ДОКАЗАТЕЛЬСТВО ТУННЕЛЯ: {}",
        if through_tunnel { "ДА" } else { "НЕТ" }
    );

    // 4. Журнал: события сохраняются на диск и переживут перезапуск процесса.
    println!("журнал: {}", journal_path.display());
    for event in session.events(50).map_err(to_string)? {
        println!("  [{:>3}] {:?} {}", event.seq, event.kind, event.message);
    }

    let stopped = session.stop().map_err(to_string)?;
    println!("остановлено: state={}", stopped.state);

    if through_tunnel {
        Ok(())
    } else {
        Err("demo did not confirm the tunnel path".to_string())
    }
}

/// Замер инициализации ядра: создание сессии, поднятие туннеля, остановка.
fn cmd_bench(args: &[String]) -> Result<(), String> {
    let iterations: usize = flag(args, "--iterations")
        .and_then(|value| value.parse().ok())
        .unwrap_or(20);
    let out = flag(args, "--out");
    let json_only = args.iter().any(|arg| arg == "--json");

    // Прогрев: первый запуск всегда дороже из-за ленивой инициализации аллокатора и DNS.
    {
        let session = CoreSession::new(CoreConfig::default_tunneled("bench-warmup", ":memory:"))
            .map_err(to_string)?;
        session.start().map_err(to_string)?;
        session.stop().map_err(to_string)?;
    }

    let mut new_ms = Vec::with_capacity(iterations);
    let mut start_ms = Vec::with_capacity(iterations);
    let mut stop_ms = Vec::with_capacity(iterations);

    for _ in 0..iterations {
        let timer = Instant::now();
        let session = CoreSession::new(CoreConfig::default_tunneled("bench", ":memory:"))
            .map_err(to_string)?;
        new_ms.push(timer.elapsed().as_secs_f64() * 1000.0);

        let timer = Instant::now();
        session.start().map_err(to_string)?;
        start_ms.push(timer.elapsed().as_secs_f64() * 1000.0);

        let timer = Instant::now();
        session.stop().map_err(to_string)?;
        stop_ms.push(timer.elapsed().as_secs_f64() * 1000.0);
    }

    let report = serde_json::json!({
        "schema": "obereg.bench/v1",
        "measured_at_ms": obereg_core::journal::now_ms(),
        "os": std::env::consts::OS,
        "arch": std::env::consts::ARCH,
        "core_api_version": CoreSession::version(),
        "iterations": iterations,
        "warmup_iterations": 1,
        "method": "CoreSession::new + start(tunnel: loopback proxy start) + stop; in-memory journal; release build",
        "metrics": {
            "session_new_ms": stats_json(&new_ms),
            "tunnel_start_ms": stats_json(&start_ms),
            "session_stop_ms": stats_json(&stop_ms),
        }
    });

    let text = serde_json::to_string_pretty(&report).map_err(to_string)?;
    if let Some(path) = &out {
        if let Some(parent) = std::path::Path::new(path).parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent).map_err(to_string)?;
            }
        }
        std::fs::write(path, &text).map_err(|err| format!("write {path}: {err}"))?;
    }

    if json_only {
        println!("{text}");
    } else {
        println!("== Obereg bench ({} итераций + прогрев) ==", iterations);
        println!(
            "{:<22} {:>8} {:>8} {:>8} {:>8} {:>8}",
            "метрика, мс", "min", "p50", "mean", "p95", "max"
        );
        for (label, values) in [
            ("session_new_ms", &new_ms),
            ("tunnel_start_ms", &start_ms),
            ("session_stop_ms", &stop_ms),
        ] {
            let stats = Stats::from(values.as_slice());
            println!(
                "{:<22} {:>8.3} {:>8.3} {:>8.3} {:>8.3} {:>8.3}",
                label, stats.min, stats.p50, stats.mean, stats.p95, stats.max
            );
        }
        if let Some(path) = &out {
            println!("отчёт: {path}");
        }
    }

    Ok(())
}

#[derive(Debug, Clone, Copy)]
struct Stats {
    min: f64,
    max: f64,
    mean: f64,
    p50: f64,
    p95: f64,
}

impl Stats {
    fn from(values: &[f64]) -> Self {
        let mut sorted = values.to_vec();
        sorted.sort_by(|left, right| left.partial_cmp(right).unwrap_or(std::cmp::Ordering::Equal));
        let min = sorted.first().copied().unwrap_or(0.0);
        let max = sorted.last().copied().unwrap_or(0.0);
        let mean = if sorted.is_empty() {
            0.0
        } else {
            sorted.iter().sum::<f64>() / sorted.len() as f64
        };
        Self {
            min,
            max,
            mean,
            p50: percentile(&sorted, 0.50),
            p95: percentile(&sorted, 0.95),
        }
    }
}

fn percentile(sorted: &[f64], fraction: f64) -> f64 {
    if sorted.is_empty() {
        return 0.0;
    }
    let index = ((sorted.len() as f64 - 1.0) * fraction).round() as usize;
    sorted[index.min(sorted.len() - 1)]
}

fn stats_json(values: &[f64]) -> serde_json::Value {
    let stats = Stats::from(values);
    serde_json::json!({
        "min": round3(stats.min),
        "max": round3(stats.max),
        "mean": round3(stats.mean),
        "p50": round3(stats.p50),
        "p95": round3(stats.p95),
        "samples": values.len(),
    })
}

fn round3(value: f64) -> f64 {
    (value * 1000.0).round() / 1000.0
}
