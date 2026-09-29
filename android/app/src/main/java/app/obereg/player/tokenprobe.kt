package app.obereg.player

import org.json.JSONObject

/**
 * Разведка движка токенов — часть P1 этапа аттестации.
 *
 * Задача этой части: понять, что именно отдаёт наша скрытая WebView, когда открывает
 * страницу сервиса. Никаких подделок: движок просто сообщает факты (есть ли конфигурация,
 * гостевые данные, объекты проверки), а следующий шаг делается по этим фактам.
 */
object TokenProbe {

    /** Страница для разведки: ролик, который сервис разрешает встраивать. */
    const val PROBE_URL = "https://www.youtube.com/embed/dQw4w9WgXcQ?enablejsapi=1&hl=ru"

    /** Скрипт разведки: возвращает компактный отчёт одним JSON. */
    fun reportScript(): String = """
        (function(){
          try {
            var out = {};
            out.url = location.href;
            out.hasYtcfg = (typeof ytcfg !== 'undefined');
            try {
              out.visitorData = (typeof ytcfg !== 'undefined' && ytcfg.get) ? ytcfg.get('VISITOR_DATA') : null;
            } catch (e) { out.visitorData = null; }
            out.hasPlayer = !!document.querySelector('#movie_player');
            out.hasVideo = !!document.querySelector('video');
            out.botguardKeys = (typeof window.botguard !== 'undefined') ? Object.keys(window.botguard).slice(0, 8) : null;
            out.jslKeys = (typeof window.jsl !== 'undefined') ? Object.keys(window.jsl).slice(0, 8) : null;
            out.bgScripts = Array.prototype.slice.call(document.scripts)
              .map(function(s){ return s.src || ''; })
              .filter(function(src){
                return src.indexOf('botguard') >= 0 || src.indexOf('/base.js') >= 0 || src.indexOf('att/') >= 0;
              })
              .slice(0, 6);
            out.scriptCount = document.scripts.length;
            return JSON.stringify(out);
          } catch (e) {
            return JSON.stringify({error: String(e)});
          }
        })()
    """.trimIndent()

    /** Сжать отчёт движка в одну понятную строку для журнала и экрана. */
    fun summarize(raw: String?): String {
        val text = PlayerApi.decodeJsString(raw)
        if (text.isNullOrEmpty()) return "движок не ответил"
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return "ответ движка не разобран: ${text.take(120)}"
        if (root.has("error")) return "ошибка в движке: ${root.optString("error").take(120)}"

        val visitor = root.optString("visitorData")
        val parts = mutableListOf(
            "конфигурация: " + if (root.optBoolean("hasYtcfg")) "есть" else "нет",
            "гостевые данные: " + if (visitor.isNullOrEmpty() || visitor == "null") {
                "нет"
            } else {
                "есть (${visitor.take(6)}…)"
            },
            "плеер: " + if (root.optBoolean("hasPlayer")) "есть" else "нет",
            "видео-элемент: " + if (root.optBoolean("hasVideo")) "есть" else "нет",
            "объект проверки: " + if (root.optJSONArray("botguardKeys") != null ||
                root.optJSONArray("jslKeys") != null
            ) {
                "найден"
            } else {
                "не найден"
            },
            "скриптов на странице: ${root.optInt("scriptCount")}",
        )
        return parts.joinToString(", ")
    }

    /**
     * Запрос проверки (шаг P2) — делается **изнутри движка**: страница сама обращается
     * к сервису со своим контекстом и ключом, а мы только читаем, что вернулось.
     *
     * Ничего не подделываем: это тот же запрос, который делает страница.
     */
    fun challengeScript(): String = """
        (async function(){
          try {
            if (typeof ytcfg === 'undefined' || !ytcfg.get) {
              return JSON.stringify({error: 'нет конфигурации страницы'});
            }
            var context = ytcfg.get('INNERTUBE_CONTEXT');
            var key = ytcfg.get('INNERTUBE_API_KEY');
            if (!context || !key) {
              return JSON.stringify({error: 'нет контекста или ключа'});
            }
            var response = await fetch('/youtubei/v1/att/get?key=' + key + '&prettyPrint=false', {
              method: 'POST',
              headers: {'Content-Type': 'application/json'},
              body: JSON.stringify({context: context})
            });
            var text = await response.text();
            var keys = [];
            try { keys = Object.keys(JSON.parse(text)).slice(0, 20); } catch (e) { keys = ['не JSON']; }
            return JSON.stringify({
              status: response.status,
              length: text.length,
              keys: keys,
              head: text.slice(0, 160)
            });
          } catch (e) {
            return JSON.stringify({error: String(e)});
          }
        })()
    """.trimIndent()

    /** Сводка ответа на запрос проверки. */
    fun summarizeChallenge(raw: String?): String {
        val text = PlayerApi.decodeJsString(raw)
        if (text.isNullOrEmpty()) return "движок не ответил на запрос проверки"
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return "ответ на проверку не разобран: ${text.take(120)}"
        if (root.has("error")) return "ошибка запроса проверки: ${root.optString("error").take(140)}"

        val status = root.optInt("status")
        val length = root.optInt("length")
        val keys = root.optJSONArray("keys")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { key -> key.isNotEmpty() } }
        }.orEmpty()
        val head = root.optString("head")
        return buildString {
            append("проверка: HTTP $status, ответ $length символов")
            if (keys.isNotEmpty()) {
                append(", поля: ")
                append(keys.take(6).joinToString(", "))
            }
            if (status != 200 && head.isNotEmpty()) {
                append(", начало: ")
                append(head.take(120))
            }
        }
    }
}
