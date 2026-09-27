/*
 * Obereg Core — публичный C ABI.
 *
 * Правила владения памятью:
 *   - obereg_core_version() возвращает статическую строку: освобождать НЕ нужно;
 *   - все прочие функции, возвращающие char*, выделяют строку в библиотеке;
 *     освобождать строго через obereg_string_free();
 *   - при ошибке функции, возвращающие char*, отдают NULL; подробности —
 *     в obereg_core_last_error();
 *   - хэндл, полученный из obereg_core_new(), освобождается obereg_core_free().
 */

#ifndef OBEREG_CORE_FFI_H
#define OBEREG_CORE_FFI_H

#ifdef __cplusplus
extern "C" {
#endif

/* Непрозрачный хэндл сессии ядра. */
typedef struct OberegHandle OberegHandle;

/* Версия публичного API ядра (статическая строка). */
const char *obereg_core_version(void);

/* JSON конфигурации по умолчанию (journal_path = ":memory:" включает журнал в памяти). */
char *obereg_core_default_config_json(const char *device_id, const char *journal_path);

/* Создать сессию. NULL — ошибка конфигурации (детали в stderr). */
OberegHandle *obereg_core_new(const char *config_json);

/* Уничтожить сессию. Безопасно вызывать с NULL. */
void obereg_core_free(OberegHandle *handle);

/* Запустить сессию. Возвращает JSON StateSnapshot или NULL. */
char *obereg_core_start(OberegHandle *handle);

/* Остановить сессию. Возвращает JSON StateSnapshot или NULL. */
char *obereg_core_stop(OberegHandle *handle);

/* Текущее состояние: JSON StateSnapshot. */
char *obereg_core_status(OberegHandle *handle);

/* Переключить режим транспорта: "direct" | "tunnel". Возвращает JSON StateSnapshot или NULL. */
char *obereg_core_set_transport_mode(OberegHandle *handle, const char *mode);

/* Журнал событий: JSON-массив JournalEvent. */
char *obereg_core_journal_read(OberegHandle *handle);

/* Проба трафика: HTTP GET через активный транспорт. Возвращает JSON TrafficProbe или NULL. */
char *obereg_core_probe_traffic(OberegHandle *handle, const char *url);

/* Адрес локального прокси ядра или NULL, если туннель не поднят. */
char *obereg_core_local_proxy_addr(OberegHandle *handle);

/* Адрес локального HTTP CONNECT-прокси или NULL, если туннель не поднят. */
char *obereg_core_local_http_proxy_addr(OberegHandle *handle);

/* 1 — туннель поднят, 0 — нет. */
int obereg_core_is_running(OberegHandle *handle);

/* Поиск в каталоге видео: JSON-массив VideoItem. */
char *obereg_core_catalog_search(OberegHandle *handle, const char *query, unsigned int limit);

/* Текст последней ошибки или NULL. */
char *obereg_core_last_error(OberegHandle *handle);

/* Освободить строку, выделенную библиотекой. */
void obereg_string_free(char *ptr);

#ifdef __cplusplus
}
#endif

#endif /* OBEREG_CORE_FFI_H */
