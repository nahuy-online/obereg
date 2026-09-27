//! Журнал туннеля: персистентность, непрерывная нумерация, перезапуск процесса.

use obereg_core::config::CoreConfig;
use obereg_core::journal::{now_ms, FileJournal, Journal, JournalEvent, JournalKind};
use obereg_core::session::CoreSession;

fn temp_dir(tag: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!(
        "obereg-test-{tag}-{}-{}",
        std::process::id(),
        now_ms()
    ));
    std::fs::create_dir_all(&dir).expect("temp dir must be created");
    dir
}

#[test]
fn file_journal_appends_and_reads_back() {
    let dir = temp_dir("append");
    let path = dir.join("journal.jsonl");

    let journal = FileJournal::open(&path).expect("journal must open");
    assert_eq!(journal.location().as_deref(), Some(path.to_str().unwrap()));

    let first = journal
        .append(&JournalEvent::new(JournalKind::SessionStart, "s1", "one"))
        .expect("append 1");
    let second = journal
        .append(
            &JournalEvent::new(JournalKind::TunnelStart, "s1", "two")
                .with_field("listen_addr", "127.0.0.1:12345"),
        )
        .expect("append 2");
    assert_eq!(first, 1);
    assert_eq!(second, 2);

    let events = journal.read_all().expect("read_all");
    assert_eq!(events.len(), 2);
    assert_eq!(events[1].kind, JournalKind::TunnelStart);
    assert_eq!(
        events[1].fields.get("listen_addr").map(String::as_str),
        Some("127.0.0.1:12345")
    );

    std::fs::remove_dir_all(&dir).ok();
}

#[test]
fn file_journal_restores_sequence_after_reopen() {
    let dir = temp_dir("seq");
    let path = dir.join("journal.jsonl");

    {
        let journal = FileJournal::open(&path).expect("journal must open");
        for index in 0..3 {
            journal
                .append(&JournalEvent::new(
                    JournalKind::TrafficProbe,
                    "s1",
                    format!("event-{index}"),
                ))
                .expect("append");
        }
    }
    // «Перезапуск процесса»: журнал открывается заново из другого экземпляра.
    let reopened = FileJournal::open(&path).expect("journal must reopen");
    let events = reopened.read_all().expect("read_all");
    assert_eq!(events.len(), 3, "all events must survive reopen");
    assert_eq!(events[2].seq, 3, "sequence must be preserved");

    let next = reopened
        .append(&JournalEvent::new(
            JournalKind::SessionStop,
            "s1",
            "after restart",
        ))
        .expect("append after reopen");
    assert_eq!(next, 4, "sequence must continue across process restarts");

    std::fs::remove_dir_all(&dir).ok();
}

#[test]
fn session_journal_survives_process_restart() {
    let dir = temp_dir("session");
    let path = dir.join("journal.jsonl");

    {
        let config = CoreConfig::default_tunneled("device-journal", path.to_str().unwrap());
        let session = CoreSession::new(config).expect("session must be created");
        let snapshot = session.start().expect("tunnel start must succeed");
        assert_eq!(
            snapshot.mode,
            obereg_core::config::TransportMode::Tunnel,
            "session must be in tunnel mode"
        );
        assert!(
            session.local_proxy_addr().is_some(),
            "local proxy must be listening"
        );
        session.stop().expect("session must stop");
        // Экземпляр сессии уничтожается здесь — имитация перезапуска процесса.
    }

    let journal = FileJournal::open(&path).expect("journal must reopen");
    let events = journal.read_all().expect("read_all");
    let kinds: Vec<JournalKind> = events.iter().map(|event| event.kind).collect();
    assert!(
        kinds.contains(&JournalKind::SessionStart),
        "kinds={kinds:?}"
    );
    assert!(kinds.contains(&JournalKind::TunnelStart), "kinds={kinds:?}");
    assert!(kinds.contains(&JournalKind::TunnelStop), "kinds={kinds:?}");
    assert!(kinds.contains(&JournalKind::SessionStop), "kinds={kinds:?}");
    // У каждого события непустая отметка времени.
    assert!(events.iter().all(|event| event.ts_ms > 0));

    std::fs::remove_dir_all(&dir).ok();
}
