//! Лента видеосервиса: разбор ответа раздела и наши собственные рекомендации.
//!
//! Зачем в ядре, а не в оболочке: разбор живёт без Android, проверяется тестами
//! и переиспользуется на других платформах. Оболочка отвечает только за сетевой запрос.
//!
//! Данные берутся из ответа раздела (`browse`) — там лента приходит глубоко вложенной
//! структурой, которая со временем меняется. Поэтому разбор не привязан к конкретному
//! пути: он обходит дерево и собирает все видео-элементы в порядке следования.
//! Если сервис переставит блоки местами, лента всё равно разберётся.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::error::{CoreError, Result};

/// Один ролик в ленте.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FeedItem {
    /// Идентификатор видео.
    pub video_id: String,
    /// Название.
    pub title: String,
    /// Канал (может быть пустым).
    pub channel: String,
    /// Длительность в виде текста сервиса («12:34»), если показана.
    pub duration: Option<String>,
    /// Счётчик просмотров словами, если показан.
    pub views: Option<String>,
    /// Обложка: первая доступная ссылка.
    pub thumbnail_url: Option<String>,
    /// Наша рекомендация (подставлена приложением, а не сервисом).
    pub injected: bool,
}

impl FeedItem {
    /// Ролик нашей рекомендации: служебная запись без данных сервиса.
    pub fn promo(video_id: &str, title: &str) -> Self {
        Self {
            video_id: video_id.to_string(),
            title: title.to_string(),
            channel: String::new(),
            duration: None,
            views: None,
            thumbnail_url: None,
            injected: true,
        }
    }
}

/// Ключи видео-элементов в ответах разных версий сервиса.
const VIDEO_KEYS: [&str; 6] = [
    "videoRenderer",
    "gridVideoRenderer",
    "compactVideoRenderer",
    "playlistVideoRenderer",
    "reelItemRenderer",
    "videoWithContextRenderer",
];

/// Разобрать ответ раздела в список роликов, сохраняя порядок ленты.
pub fn parse_feed(json: &str) -> Result<Vec<FeedItem>> {
    let root: Value = serde_json::from_str(json)
        .map_err(|error| CoreError::Config(format!("лента: не разобран JSON: {error}")))?;
    let mut items = Vec::new();
    collect(&root, &mut items);
    Ok(items)
}

/// Обход дерева: собираем видео-элементы в том порядке, в каком их отдал сервис.
fn collect(node: &Value, items: &mut Vec<FeedItem>) {
    match node {
        Value::Object(map) => {
            for key in VIDEO_KEYS {
                if let Some(video) = map.get(key) {
                    if let Some(item) = read_video(video) {
                        items.push(item);
                    }
                }
            }
            for value in map.values() {
                collect(value, items);
            }
        }
        Value::Array(array) => {
            for value in array {
                collect(value, items);
            }
        }
        _ => {}
    }
}

/// Прочитать один видео-элемент.
fn read_video(video: &Value) -> Option<FeedItem> {
    let video_id = video.get("videoId").and_then(Value::as_str)?.to_string();
    if video_id.is_empty() {
        return None;
    }
    let title = text_of(video.get("title"))
        .or_else(|| text_of(video.get("headline")))
        .unwrap_or_default();
    let channel = text_of(video.get("ownerText"))
        .or_else(|| text_of(video.get("longBylineText")))
        .or_else(|| text_of(video.get("shortBylineText")))
        .unwrap_or_default();
    let duration = text_of(video.get("lengthText"));
    let views =
        text_of(video.get("viewCountText")).or_else(|| text_of(video.get("shortViewCountText")));
    let thumbnail_url = thumbnail_of(video);
    Some(FeedItem {
        video_id,
        title,
        channel,
        duration,
        views,
        thumbnail_url,
        injected: false,
    })
}

/// Текст из блока вида `{"runs": [{"text": "..."}]}`, `{"simpleText": "..."}` или `{"content": "..."}`.
fn text_of(node: Option<&Value>) -> Option<String> {
    let node = node?;
    if let Some(simple) = node.get("simpleText").and_then(Value::as_str) {
        return Some(simple.to_string());
    }
    if let Some(content) = node.get("content").and_then(Value::as_str) {
        return Some(content.to_string());
    }
    if let Some(runs) = node.get("runs").and_then(Value::as_array) {
        let text: String = runs
            .iter()
            .filter_map(|run| run.get("text").and_then(Value::as_str))
            .collect();
        if !text.is_empty() {
            return Some(text);
        }
    }
    None
}

/// Первая ссылка на обложку из блока `thumbnail`.
fn thumbnail_of(video: &Value) -> Option<String> {
    let thumbs = video.get("thumbnail")?.get("thumbnails")?.as_array()?;
    let first = thumbs.first()?;
    first.get("url").and_then(Value::as_str).map(str::to_string)
}

/// Вставить нашу рекомендацию на заданную позицию ленты.
///
/// Позиция считается по порядку ленты, начиная с единицы: `position = 3` — третьим роликом.
/// Если такой ролик уже есть в ленте, он убирается, чтобы не было двух одинаковых записей.
pub fn inject(items: &[FeedItem], promo: &FeedItem, position: usize) -> Vec<FeedItem> {
    let mut result: Vec<FeedItem> = items
        .iter()
        .filter(|item| item.video_id != promo.video_id)
        .cloned()
        .collect();
    let index = position.saturating_sub(1).min(result.len());
    result.insert(index, promo.clone());
    result
}

/// Собрать промо-ролик для вставки: идентификатор обязателен, название можно не указывать.
pub fn promo_from(video_id: &str, title: Option<&str>) -> FeedItem {
    FeedItem::promo(video_id, title.unwrap_or("рекомендуем"))
}

/// Плейлист в списке плейлистов.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlaylistEntry {
    /// Идентификатор плейлиста (без префикса VL).
    pub playlist_id: String,
    /// Название плейлиста.
    pub title: String,
    /// Сколько роликов (текстом сервиса, если показано).
    pub video_count: Option<String>,
    /// Обложка.
    pub thumbnail_url: Option<String>,
}

/// Ключи плейлистов в ответах разных версий сервиса.
const PLAYLIST_KEYS: [&str; 4] = [
    "gridPlaylistRenderer",
    "playlistRenderer",
    "lockupViewModel",
    "gridPlaylistRendererWithContext",
];

/// Разобрать ответ раздела в список плейлистов, сохраняя порядок.
pub fn parse_playlists(json: &str) -> Result<Vec<PlaylistEntry>> {
    let root: Value = serde_json::from_str(json)
        .map_err(|error| CoreError::Config(format!("плейлисты: не разобран JSON: {error}")))?;
    let mut entries = Vec::new();
    collect_playlists(&root, &mut entries);
    Ok(entries)
}

fn collect_playlists(node: &Value, entries: &mut Vec<PlaylistEntry>) {
    match node {
        Value::Object(map) => {
            for key in PLAYLIST_KEYS {
                if let Some(playlist) = map.get(key) {
                    if let Some(entry) = read_playlist(playlist) {
                        entries.push(entry);
                    }
                }
            }
            for value in map.values() {
                collect_playlists(value, entries);
            }
        }
        Value::Array(array) => {
            for value in array {
                collect_playlists(value, entries);
            }
        }
        _ => {}
    }
}

/// Прочитать один плейлист: идентификатор, название, число роликов, обложка.
fn read_playlist(playlist: &Value) -> Option<PlaylistEntry> {
    // Обычный вид: playlistId — строка; новый вид (lockupViewModel) — тоже строкой.
    let raw_id = playlist
        .get("playlistId")
        .and_then(Value::as_str)
        .or_else(|| playlist.get("contentId").and_then(Value::as_str))
        .unwrap_or_default();
    let playlist_id = raw_id.trim_start_matches("VL").to_string();
    if playlist_id.is_empty() {
        return None;
    }
    let title = text_of(playlist.get("title"))
        .or_else(|| {
            text_of(
                playlist
                    .get("metadata")
                    .and_then(|m| m.get("lockupMetadataViewModel"))
                    .and_then(|m| m.get("title")),
            )
        })
        .unwrap_or_default();
    let video_count = text_of(playlist.get("videoCountText"))
        .or_else(|| text_of(playlist.get("videoCountShortText")))
        .or_else(|| {
            playlist
                .get("thumbnailOverlays")
                .and_then(|overlays| overlays.as_array())
                .and_then(|items| items.first())
                .and_then(|first| first.get("thumbnailOverlayTimeStatusRenderer"))
                .and_then(|status| text_of(status.get("text")))
        });
    let thumbnail_url = thumbnail_of(playlist);
    Some(PlaylistEntry {
        playlist_id,
        title,
        video_count,
        thumbnail_url,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Небольшой образец ленты: две полки, плейлист-элемент и вложенные обёртки.
    fn sample() -> String {
        r#"{
          "contents": {
            "twoColumnBrowseResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "richGridRenderer": {
                      "contents": [
                        {"richItemRenderer": {"content": {"videoRenderer": {
                          "videoId": "aaaaaaaaaaa",
                          "title": {"runs": [{"text": "Первый ролик"}]},
                          "ownerText": {"runs": [{"text": "Канал А"}]},
                          "lengthText": {"simpleText": "12:34"},
                          "viewCountText": {"simpleText": "1 234 просмотра"},
                          "thumbnail": {"thumbnails": [{"url": "https://i.example/1.jpg"}]}
                        }}}},
                        {"richItemRenderer": {"content": {"videoRenderer": {
                          "videoId": "bbbbbbbbbbb",
                          "title": {"runs": [{"text": "Второй ролик"}]},
                          "ownerText": {"runs": [{"text": "Канал Б"}]}
                        }}}},
                        {"shelfRenderer": {"content": {"horizontalListRenderer": {"items": [
                          {"gridVideoRenderer": {
                            "videoId": "ccccccccccc",
                            "title": {"simpleText": "Третий ролик"},
                            "shortBylineText": {"runs": [{"text": "Канал В"}]}
                          }},
                          {"playlistVideoRenderer": {
                            "videoId": "ddddddddddd",
                            "title": {"runs": [{"text": "Четвёртый ролик"}]}
                          }}
                        ]}}}}
                      ]
                    }
                  }
                }
              }]
            }
          }
        }"#
        .to_string()
    }

    #[test]
    fn parses_feed_in_order() {
        let items = parse_feed(&sample()).expect("лента должна разбираться");
        let ids: Vec<&str> = items.iter().map(|item| item.video_id.as_str()).collect();
        assert_eq!(
            ids,
            ["aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd"]
        );
        assert_eq!(items[0].title, "Первый ролик");
        assert_eq!(items[0].channel, "Канал А");
        assert_eq!(items[2].channel, "Канал В");
        assert_eq!(items[0].duration.as_deref(), Some("12:34"));
        assert_eq!(items[0].views.as_deref(), Some("1 234 просмотра"));
        assert!(items[0].thumbnail_url.is_some());
        assert!(!items[0].injected);
    }

    #[test]
    fn injects_promo_at_position() {
        let items = parse_feed(&sample()).expect("лента");
        let promo = promo_from("zzzzzzzzzzz", Some("Наш выбор"));
        let with_promo = inject(&items, &promo, 3);
        assert_eq!(with_promo.len(), 5);
        assert_eq!(with_promo[2].video_id, "zzzzzzzzzzz");
        assert!(with_promo[2].injected);
        // Порядок остальных не сломан.
        assert_eq!(with_promo[0].video_id, "aaaaaaaaaaa");
        assert_eq!(with_promo[3].video_id, "ccccccccccc");
    }

    #[test]
    fn promo_already_in_feed_is_moved_not_duplicated() {
        let items = parse_feed(&sample()).expect("лента");
        let promo = promo_from("bbbbbbbbbbb", Some("Наш выбор"));
        let with_promo = inject(&items, &promo, 1);
        assert_eq!(with_promo.len(), 4, "повтор не должен появляться");
        assert_eq!(with_promo[0].video_id, "bbbbbbbbbbb");
        assert!(with_promo[0].injected);
        assert_eq!(
            with_promo
                .iter()
                .filter(|i| i.video_id == "bbbbbbbbbbb")
                .count(),
            1
        );
    }

    #[test]
    fn position_beyond_feed_appends() {
        let items = parse_feed(&sample()).expect("лента");
        let promo = promo_from("zzzzzzzzzzz", None);
        let with_promo = inject(&items, &promo, 99);
        assert_eq!(
            with_promo.last().expect("последний").video_id,
            "zzzzzzzzzzz"
        );
    }

    #[test]
    fn broken_json_is_reported() {
        assert!(parse_feed("{не json}").is_err());
        let empty = parse_feed("{\"contents\": {}}").expect("пустая лента");
        assert!(empty.is_empty());
    }

    /// Образец списка плейлистов: обычный вид и новый вид (lockupViewModel).
    fn playlists_sample() -> String {
        r#"{
          "contents": {
            "gridRenderer": {
              "items": [
                {"gridPlaylistRenderer": {
                  "playlistId": "PL1111111111111111",
                  "title": {"runs": [{"text": "Мой плейлист"}]},
                  "videoCountText": {"runs": [{"text": "12 videos"}]},
                  "thumbnail": {"thumbnails": [{"url": "https://i.example/pl1.jpg"}]}
                }},
                {"lockupViewModel": {
                  "contentId": "VLPL2222222222222222",
                  "metadata": {"lockupMetadataViewModel": {"title": {"content": "Плейлист из нового вида"}}}
                }}
              ]
            }
          }
        }"#
        .to_string()
    }

    #[test]
    fn parses_playlists_in_order() {
        let entries = parse_playlists(&playlists_sample()).expect("плейлисты должны разбираться");
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].playlist_id, "PL1111111111111111");
        assert_eq!(entries[0].title, "Мой плейлист");
        assert_eq!(entries[0].video_count.as_deref(), Some("12 videos"));
        assert!(entries[0].thumbnail_url.is_some());
        assert_eq!(entries[1].playlist_id, "PL2222222222222222");
        assert_eq!(entries[1].title, "Плейлист из нового вида");
    }

    #[test]
    fn playlists_survive_missing_fields() {
        let json = r#"{"contents":{"items":[{"gridPlaylistRenderer":{"title":{"simpleText":"без идентификатора"}}}]}}"#;
        let entries = parse_playlists(json).expect("разбор");
        assert!(
            entries.is_empty(),
            "без идентификатора запись отбрасывается"
        );
        assert!(parse_playlists("{не json}").is_err());
    }
}
