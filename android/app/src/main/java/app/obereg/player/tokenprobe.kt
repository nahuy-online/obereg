package app.obereg.player

import org.json.JSONObject

/**
 * Разведка и проверка движка — этап аттестации (P1, P2, P3).
 *
 * Задача: понять, что отдаёт наша скрытая WebView, запросить у сервиса проверку и
 * загрузить его программу в движок. Никаких подделок: движок сообщает факты, а решение
 * о следующем шаге принимается по ним.
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
        if (root.has("error")) return "ошибка в движке: ${root.optString("error").take(140)}"

        val visitor = root.optString("visitorData")
        val pageUrl = root.optString("url").take(80)
        return listOf(
            "страница: " + pageUrl.ifEmpty { "?" },
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
        ).joinToString(", ")
    }

    /**
     * Запрос проверки (шаг P2): страница сама обращается к сервису со своим контекстом и ключом.
     *
     * Результат складывается в `window.__oberegChallenge`, а полный текст ответа —
     * в `window.__oberegLastChallenge`: его потом разбирает шаг 3, не дёргая сервис повторно
     * (частые запросы проверки сервис отвечает пустышкой — проверено на устройстве).
     */
    fun challengeScript(): String = """
        (function(){
          var stages = [];
          window.__oberegStages = '';
          window.__oberegChallenge = '';
          function mark(name) {
            stages.push(name);
            window.__oberegStages = stages.join(' → ');
          }
          try {
            // Свежая проверка (до 5 минут) переиспользуется: частые запросы сервис игнорирует.
            if (window.__oberegLastChallenge && window.__oberegLastChallengeAt) {
              var ageSeconds = Math.round((Date.now() - window.__oberegLastChallengeAt) / 1000);
              if (ageSeconds < 300) {
                mark('использую сохранённую проверку (' + ageSeconds + ' с)');
                window.__oberegChallenge = JSON.stringify({
                  status: 200,
                  length: window.__oberegLastChallenge.length,
                  keys: [],
                  head: '',
                  reused: true,
                  ageSeconds: ageSeconds
                });
                return 'started';
              }
            }
            if (typeof ytcfg === 'undefined' || !ytcfg.get) {
              window.__oberegChallenge = JSON.stringify({error: 'нет конфигурации страницы'});
              return 'started';
            }
            var context = ytcfg.get('INNERTUBE_CONTEXT');
            var key = ytcfg.get('INNERTUBE_API_KEY');
            if (!context || !key) {
              window.__oberegChallenge = JSON.stringify({error: 'нет контекста или ключа'});
              return 'started';
            }
            mark('контекст и ключ есть');
            setTimeout(function(){
              if (window.__oberegChallenge === '') {
                window.__oberegChallenge = JSON.stringify({
                  error: 'сервис не ответил за 8 секунд',
                  stages: stages.join(' → ')
                });
              }
            }, 8000);
            mark('запрос отправлен');
            fetch('/youtubei/v1/att/get?key=' + key + '&prettyPrint=false', {
              method: 'POST',
              headers: {'Content-Type': 'application/json'},
              body: JSON.stringify({context: context})
            }).then(function(response){
              mark('ответ получен: HTTP ' + response.status);
              return response.text().then(function(text){
                mark('тело прочитано: ' + text.length + ' символов');
                window.__oberegLastChallenge = text;
                window.__oberegLastChallengeAt = Date.now();
                var keys = [];
                try { keys = Object.keys(JSON.parse(text)).slice(0, 20); } catch (e) { keys = ['не JSON']; }
                window.__oberegChallenge = JSON.stringify({
                  status: response.status,
                  length: text.length,
                  keys: keys,
                  head: text.slice(0, 240)
                });
              });
            }).catch(function(e){
              window.__oberegChallenge = JSON.stringify({error: 'сеть: ' + String(e), stages: stages.join(' → ')});
            });
          } catch (e) {
            window.__oberegChallenge = JSON.stringify({error: String(e), stages: stages.join(' → ')});
          }
          return 'started';
        })()
    """.trimIndent()

    /** Забрать ответ проверки и этапы: «ответ||этапы». */
    fun challengePollScript(): String =
        "(window.__oberegChallenge || '') + '||' + (window.__oberegStages || '')"

    /** Разделить ответ опроса на результат и этапы. */
    fun splitPoll(raw: String?): Pair<String, String> {
        val text = PlayerApi.decodeJsString(raw).orEmpty()
        val parts = text.split("||", limit = 2)
        val result = parts.getOrNull(0).orEmpty().trim()
        val stages = parts.getOrNull(1).orEmpty().trim()
        return result to stages
    }

    /** Сводка ответа на запрос проверки (на вход — обычный JSON-текст). */
    fun summarizeChallenge(jsonText: String?): String {
        if (jsonText.isNullOrEmpty()) return "движок не ответил на запрос проверки"
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return "ответ на проверку не разобран: ${jsonText.take(120)}"
        if (root.has("error")) {
            val stages = root.optString("stages")
            return buildString {
                append("ошибка запроса проверки: ")
                append(root.optString("error").take(160))
                if (stages.isNotEmpty()) {
                    append(" (этапы: ")
                    append(stages.take(140))
                    append(")")
                }
            }
        }

        val status = root.optInt("status")
        val length = root.optInt("length")
        val reused = root.optBoolean("reused")
        val age = root.optInt("ageSeconds")
        val keys = root.optJSONArray("keys")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { key -> key.isNotEmpty() } }
        }.orEmpty()
        val head = root.optString("head")
        return buildString {
            append("проверка: HTTP $status, ответ $length символов")
            if (reused) {
                append(" (сохранённая, возраст $age с — сервис не дёргаем)")
            }
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

    /**
     * Разбор сохранённой проверки (шаг P3): структура и что получается после загрузки
     * программы сервиса в движок. Никаких новых запросов — работаем с тем, что уже есть.
     */
    fun challengeDetailScript(): String = """
        (function(){
          try {
            var text = window.__oberegLastChallenge;
            if (!text) {
              window.__oberegChallenge = JSON.stringify({error: 'сохранённой проверки нет — сначала шаг 2'});
              return 'started';
            }
            var root = {};
            var parseError = null;
            try { root = JSON.parse(text); } catch (e) { parseError = String(e); }
            var challenge = root.challenge || {};
            var botguard = root.botguardData || {};
            var detail = {
              status: 200,
              length: text.length,
              parseError: parseError,
              rootKeys: Object.keys(root).slice(0, 12),
              challengeKeys: Object.keys(challenge).slice(0, 12),
              botguardKeys: Object.keys(botguard).slice(0, 12),
              programType: typeof botguard.program,
              programLength: (typeof botguard.program === 'string') ? botguard.program.length : -1,
              challengeProgramType: (typeof challenge.program === 'string') ? 'string' : typeof challenge.program,
              loadedKeys: null,
              botguardGlobal: null
            };
            if (typeof botguard.program === 'string' && botguard.program.length > 100) {
              try {
                (0, eval)(botguard.program);
                detail.loadedKeys = (typeof window.botguard !== 'undefined') ? Object.keys(window.botguard).slice(0, 12) : null;
              } catch (e) {
                detail.loadError = String(e);
              }
            }
            window.__oberegChallenge = JSON.stringify(detail);
          } catch (e) {
            window.__oberegChallenge = JSON.stringify({error: String(e)});
          }
          return 'started';
        })()
    """.trimIndent()

    /** Сводка разбора проверки: что за структура и что дала загрузка программы. */
    fun summarizeChallengeDetail(jsonText: String?): String {
        if (jsonText.isNullOrEmpty()) return "разбор проверки: ответа нет"
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return "разбор проверки не прочитан: ${jsonText.take(140)}"
        if (root.has("error")) {
            val stages = root.optString("stages")
            return "разбор проверки: ${root.optString("error").take(160)}" +
                if (stages.isEmpty()) "" else " (этапы: ${stages.take(140)})"
        }

        val length = root.optInt("length")
        val parseError = root.optString("parseError").takeIf { it.isNotEmpty() && it != "null" }
        val challengeKeys = root.optJSONArray("challengeKeys")?.let(::names).orEmpty()
        val botguardKeys = root.optJSONArray("botguardKeys")?.let(::names).orEmpty()
        val programType = root.optString("programType")
        val programLength = root.optInt("programLength", -1)
        val loaded = root.optJSONArray("loadedKeys")?.let(::names)
        val botguardGlobal = root.optJSONArray("botguardGlobal")?.let(::names)

        return buildString {
            append("разбор проверки: HTTP 200, ответ $length символов")
            if (parseError != null) {
                append(", ОШИБКА разбора JSON: ${parseError.take(80)}")
            }
            append(", корень: ${challengeKeys.size + botguardKeys.size + 1} полей")
            append(", проверка: ${challengeKeys.take(4).joinToString(", ").ifEmpty { "пусто" }}")
            append(", программа: ${programType}${if (programLength >= 0) " ($programLength символов)" else ""}")
            if (botguardKeys.isNotEmpty()) {
                append(", данные: ${botguardKeys.take(4).joinToString(", ")}")
            }
            if (loaded != null) {
                append(", после загрузки: ${loaded.take(4).joinToString(", ").ifEmpty { "ничего" }}")
            }
            if (root.has("loadError")) append(", ошибка загрузки: ${root.optString("loadError").take(80)}")
        }
    }

    /** Достать программу сервиса из сохранённой проверки (её вкладывает приложение). */
    fun programExtractScript(): String = """
        (function(){
          try {
            var text = window.__oberegLastChallenge;
            if (!text) { return ''; }
            var root = JSON.parse(text);
            var botguard = root.botguardData || {};
            return (typeof botguard.program === 'string') ? botguard.program : '';
          } catch (e) {
            return '';
          }
        })()
    """.trimIndent()

    /** Что появилось после вложения программы. */
    fun programCheckScript(): String = """
        (function(){
          try {
            var out = {
              botguardType: (typeof window.botguard),
              botguardKeys: (typeof window.botguard !== 'undefined') ? Object.keys(window.botguard).slice(0, 12) : null,
              jslType: (typeof window.jsl)
            };
            return JSON.stringify(out);
          } catch (e) {
            return JSON.stringify({error: String(e)});
          }
        })()
    """.trimIndent()

    /** Сводка шага 4. */
    fun summarizeProgramCheck(jsonText: String?): String {
        if (jsonText.isNullOrEmpty()) return "вложение программы: движок не ответил"
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return "вложение программы не прочитано: ${jsonText.take(120)}"
        if (root.has("error")) return "вложение программы: ошибка ${root.optString("error").take(120)}"
        val type = root.optString("botguardType")
        val keys = root.optJSONArray("botguardKeys")?.let(::names).orEmpty()
        val jsl = root.optString("jslType")
        return buildString {
            append("вложение программы: botguard=$type")
            if (keys.isNotEmpty()) append(" (ключи: ${keys.take(6).joinToString(", ")})")
            append(", jsl=$jsl")
        }
    }

    /** Разбор программы: начало текста и адрес интерпретатора (его подгружает приложение). */
    fun programIntrospectScript(): String = """
        (function(){
          try {
            var text = window.__oberegLastChallenge;
            if (!text) { return JSON.stringify({error: 'нет сохранённой проверки'}); }
            var root = JSON.parse(text);
            var data = root.botguardData || {};
            var program = (typeof data.program === 'string') ? data.program : '';
            return JSON.stringify({
              programLength: program.length,
              programHead: program.slice(0, 400),
              interpreterUrl: (typeof data.interpreterSafeUrl === 'string') ? data.interpreterSafeUrl : null,
              hasBotguardNow: (typeof window.botguard !== 'undefined'),
              hasInterpreterNow: (typeof window.__oberegInterpreterText === 'string')
            });
          } catch (e) {
            return JSON.stringify({error: String(e)});
          }
        })()
    """.trimIndent()

    /** Сводка разбора программы. */
    fun summarizeIntrospect(jsonText: String?): String {
        if (jsonText.isNullOrEmpty()) return "разбор программы: движок не ответил"
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return "разбор программы не прочитан: ${jsonText.take(120)}"
        if (root.has("error")) return "разбор программы: ${root.optString("error").take(140)}"
        val head = root.optString("programHead").replace("\n", " ").take(180)
        val url = root.optString("interpreterUrl")
        return buildString {
            append("разбор программы: ${root.optInt("programLength")} символов")
            append(", начало: «${head.take(120)}…»")
            append(", интерпретатор: ")
            append(if (url.isEmpty() || url == "null") "нет" else "есть (${url.take(60)}…)")
            append(", botguard сейчас: ${if (root.optBoolean("hasBotguardNow")) "есть" else "нет"}")
        }
    }

    /**
     * Поиск адреса интерпретатора.
     *
     * Имена полей в обёртке меняются, поэтому не угадываем: собираем все ссылки
     * из данных проверки обходом.
     */
    fun interpreterFindScript(): String = """
        (function(){
          function collect(obj, depth, found) {
            if (!obj || depth > 6 || found.length >= 6) { return; }
            if (typeof obj === 'string') {
              var isLink = obj.indexOf('http') === 0 || obj.indexOf('/') === 0;
              var looksScript = obj.indexOf('.js') > 0 || obj.indexOf('interpreter') >= 0;
              if ((isLink || looksScript) && obj.length <= 300 && found.indexOf(obj) < 0) { found.push(obj); }
              return;
            }
            if (typeof obj === 'object') {
              for (var key in obj) {
                collect(obj[key], depth + 1, found);
              }
            }
          }
          try {
            var text = window.__oberegLastChallenge;
            if (!text) { return JSON.stringify({error: 'нет сохранённой проверки'}); }
            var root = JSON.parse(text);
            var data = root.botguardData || {};
            var found = [];
            collect(data, 0, found);
            // Структура обёртки: имена полей и превью значений — по ним видно, где спрятан адрес.
            var wrapperKeys = [];
            var wrapperPreview = [];
            try {
              var wrapper = data.interpreterSafeUrl;
              if (wrapper && typeof wrapper === 'object') {
                wrapperKeys = Object.keys(wrapper).slice(0, 10);
                for (var wi = 0; wi < wrapperKeys.length; wi++) {
                  var wv = wrapper[wrapperKeys[wi]];
                  var wp = (typeof wv === 'string') ? wv.slice(0, 120) : (typeof wv);
                  wrapperPreview.push(wrapperKeys[wi] + ' = ' + wp);
                }
              }
            } catch (we) { wrapperPreview.push('ошибка чтения обёртки'); }
            return JSON.stringify({
              botguardKeys: Object.keys(data).slice(0, 12),
              urlType: typeof data.interpreterSafeUrl,
              wrapperKeys: wrapperKeys,
              wrapperPreview: wrapperPreview.slice(0, 6),
              urls: found,
              hasInterpreterText: (typeof window.__oberegInterpreterText === 'string')
            });
          } catch (e) {
            return JSON.stringify({error: String(e)});
          }
        })()
    """.trimIndent()

    /** Сводка поиска адреса интерпретатора. */
    fun summarizeInterpreterFind(jsonText: String?): String {
        if (jsonText.isNullOrEmpty()) return "поиск интерпретатора: движок не ответил"
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return "поиск интерпретатора не прочитан: ${jsonText.take(120)}"
        if (root.has("error")) return "поиск интерпретатора: ${root.optString("error").take(140)}"
        val urls = root.optJSONArray("urls")?.let(::names).orEmpty()
        val keys = root.optJSONArray("botguardKeys")?.let(::names).orEmpty()
        val preview = root.optJSONArray("wrapperPreview")?.let(::names).orEmpty()
        val wrapperKeys = root.optJSONArray("wrapperKeys")?.let(::names).orEmpty()
        return buildString {
            append("поиск интерпретатора: поля данных — ${keys.take(6).joinToString(", ").ifEmpty { "пусто" }}")
            if (wrapperKeys.isNotEmpty()) append(", обёртка: ${wrapperKeys.take(5).joinToString(", ")}")
            if (preview.isNotEmpty()) append(", превью: ${preview.first().take(120)}")
            append(", ссылок: ${urls.size}")
            if (urls.isNotEmpty()) append(", первая: ${urls.first().take(90)}…")
        }
    }

    /** Снимок имён на странице и сравнение: что появилось после вложения интерпретатора. */
    fun globalsSnapshotScript(): String = """
        (function(){
          try {
            if (!window.__oberegKeysBefore) {
              window.__oberegKeysBefore = Object.keys(window).join(',');
              return 'saved';
            }
            var before = window.__oberegKeysBefore.split(',');
            var now = Object.keys(window);
            var added = [];
            for (var i = 0; i < now.length; i++) {
              var key = now[i];
              if (before.indexOf(key) >= 0) { continue; }
              if (key.indexOf('__obereg') === 0) { continue; }
              added.push(key + ':' + (typeof window[key]));
            }
            return JSON.stringify({ added: added.slice(0, 25) });
          } catch (e) {
            return JSON.stringify({error: String(e)});
          }
        })()
    """.trimIndent()

    /** Сводка сравнения имён. */
    fun summarizeGlobalsDiff(jsonText: String?): String {
        if (jsonText.isNullOrEmpty()) return "сравнение имён: движок не ответил"
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return "сравнение имён не прочитано: ${jsonText.take(120)}"
        if (root.has("error")) return "сравнение имён: ${root.optString("error").take(120)}"
        val added = root.optJSONArray("added")?.let(::names).orEmpty()
        return if (added.isEmpty()) {
            "сравнение имён: новых сущностей нет"
        } else {
            "после интерпретатора появились: ${added.take(8).joinToString(", ")}"
        }
    }

    private fun names(array: org.json.JSONArray): List<String> =
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { name -> name.isNotEmpty() } }
}