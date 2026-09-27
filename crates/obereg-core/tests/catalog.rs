//! Каталог видео: локальный источник, поиск и подборка.

use obereg_core::catalog::{default_catalog, CatalogSource, LocalCatalogSource, VideoItem};

#[test]
fn demo_catalog_parses_and_is_not_empty() {
    let catalog = default_catalog().expect("demo catalog must parse");
    assert!(catalog.len() >= 4, "каталог должен содержать элементы");
    assert!(!catalog.is_empty());
}

#[test]
fn search_is_case_insensitive_and_matches_title() {
    let catalog = default_catalog().expect("catalog");
    let found = catalog.search("SAMPLE-10S", 10).expect("search must work");
    assert_eq!(
        found.len(),
        1,
        "ожидается одно совпадение, найдено {found:?}"
    );
    assert_eq!(found[0].id, "sample-10s");
    assert!(
        found[0].url.starts_with("https://"),
        "элемент должен содержать ссылку на поток"
    );
}

#[test]
fn search_matches_id_and_respects_limit() {
    let catalog = default_catalog().expect("catalog");
    let by_id = catalog.search("sample-10s", 10).expect("search");
    assert_eq!(by_id.len(), 1);
    assert_eq!(by_id[0].id, "sample-10s");

    let limited = catalog.search("тестовое", 2).expect("search");
    assert!(limited.len() <= 2, "лимит должен соблюдаться");
}

#[test]
fn empty_query_returns_catalog_in_order() {
    let catalog = default_catalog().expect("catalog");
    let all = catalog.search("", 100).expect("search");
    assert_eq!(all.len(), catalog.len());
    assert_eq!(all[0].id, "local-sample");
}

#[test]
fn unknown_query_returns_nothing() {
    let catalog = default_catalog().expect("catalog");
    let found = catalog.search("нет-такого-видео-42", 10).expect("search");
    assert!(found.is_empty());
}

#[test]
fn featured_respects_limit() {
    let catalog = default_catalog().expect("catalog");
    let featured = catalog.featured(3).expect("featured");
    assert_eq!(featured.len(), 3);
    assert_eq!(featured[0].id, "local-sample");
}

#[test]
fn source_from_json_assigns_local_source_by_default() {
    let json = r#"[{"id":"a","title":"Заголовок","url":"https://example.com/a.mp4"}]"#;
    let catalog = LocalCatalogSource::from_json(json).expect("catalog from json");
    let items = catalog.search("", 10).expect("search");
    assert_eq!(items.len(), 1);
    assert_eq!(items[0].source, "local", "источник должен проставиться");
    assert_eq!(items[0].duration_secs, None);
}

#[test]
fn malformed_json_is_rejected_with_error() {
    assert!(LocalCatalogSource::from_json("{not json}").is_err());
}

#[test]
fn custom_source_keeps_declared_source_name() {
    let source = LocalCatalogSource::with_items(vec![VideoItem {
        id: "x".to_string(),
        title: "Своё видео".to_string(),
        url: "https://example.com/x.mp4".to_string(),
        duration_secs: Some(10),
        source: "external".to_string(),
    }]);
    assert_eq!(source.name(), "local");
    let items = source.search("своё", 5).expect("search");
    assert_eq!(items[0].source, "external");
}
