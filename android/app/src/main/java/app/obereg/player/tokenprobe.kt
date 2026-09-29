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
}
