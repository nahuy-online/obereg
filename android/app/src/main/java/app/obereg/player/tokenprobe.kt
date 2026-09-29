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
            var names = Object.getOwnPropertyNames(window);
            if (!window.__oberegNamesBefore) {
              window.__oberegNamesBefore = names.join(',');
              return 'saved';
            }
            var before = window.__oberegNamesBefore.split(',');
            var added = [];
            for (var i = 0; i < names.length; i++) {
              var key = names[i];
              if (before.indexOf(key) >= 0) { continue; }
              if (key.indexOf('__obereg') === 0) { continue; }
              var kind = 'недоступно';
              try { kind = typeof window[key]; } catch (e2) { }
              added.push(key + ':' + kind);
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

    /** Ставит перехват ошибок страницы — чтобы видеть сбои вложенного кода. */
    fun armErrorsScript(): String = """
        (function(){
          window.__oberegErrs = [];
          window.onerror = function(message, source, line, column) {
            window.__oberegErrs.push(String(message).slice(0, 140) + ' @' + line + ':' + column);
            return false;
          };
          return 'armed';
        })()
    """.trimIndent()

    /** Чтение перехваченных ошибок. */
    fun errorsReportScript(): String =
        "JSON.stringify({ errs: (window.__oberegErrs || []).slice(0, 3) })"

    /** Сводка ошибок страницы (пустая строка, если их нет). */
    fun summarizeErrors(jsonText: String?): String {
        if (jsonText.isNullOrEmpty()) return ""
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return ""
        val errs = root.optJSONArray("errs")?.let(::names).orEmpty()
        return if (errs.isEmpty()) "" else "ошибки страницы: ${errs.take(2).joinToString("; ").take(200)}"
    }

    /**
     * Ищет машину проверки: объект с методом `a` — именно так её находит эталонный клиент.
     * Дополнительно показывает короткие строки из проверки (там бывает имя объекта).
     */
    fun vmFindScript(): String = """
        (function(){
          try {
            var names = Object.getOwnPropertyNames(window);
            var candidates = [];
            for (var i = 0; i < names.length; i++) {
              var key = names[i];
              if (key.indexOf('__obereg') === 0) { continue; }
              var value;
              try { value = window[key]; } catch (e2) { continue; }
              if (!value || (typeof value !== 'object' && typeof value !== 'function')) { continue; }
              var hasA = false;
              try { hasA = typeof value.a === 'function'; } catch (e3) { hasA = false; }
              if (!hasA) { continue; }
              var fields = [];
              try {
                var own = Object.getOwnPropertyNames(value).slice(0, 8);
                for (var j = 0; j < own.length; j++) { fields.push(own[j] + ':' + (typeof value[own[j]])); }
              } catch (e4) { }
              candidates.push(key + '(' + fields.join(',') + ')');
            }
            var fromChallenge = '';
            try {
              var root = JSON.parse(window.__oberegLastChallenge || '{}');
              var challenge = root.challenge || [];
              for (var k = 0; k < challenge.length; k++) {
                var item = challenge[k];
                var short = (typeof item === 'string') && item.length >= 1 && item.length <= 24;
                if (short && /^[A-Za-z_][A-Za-z0-9_]*$/.test(item)) { fromChallenge = k + ':' + item; break; }
              }
            } catch (e5) { }
            var picked = '';
            if (candidates.length > 0) {
              var head = candidates[0];
              var cut = head.indexOf('(');
              if (cut > 0) { picked = head.slice(0, cut); }
            } else if (fromChallenge) {
              var name2 = fromChallenge.slice(fromChallenge.indexOf(':') + 1);
              try {
                if (window[name2] && typeof window[name2].a === 'function') { picked = name2; }
              } catch (e6) { }
            }
            if (picked) { window.__oberegVmName = picked; }
            return JSON.stringify({
              candidates: candidates.slice(0, 6),
              fromChallenge: fromChallenge,
              picked: picked
            });
          } catch (e) {
            return JSON.stringify({error: String(e)});
          }
        })()
    """.trimIndent()

    /** Сводка поиска машины проверки. */
    fun summarizeVmFind(jsonText: String?): String {
        if (jsonText.isNullOrEmpty()) return "машина проверки: движок не ответил"
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return "машина проверки не прочитана: ${jsonText.take(120)}"
        if (root.has("error")) return "машина проверки: ${root.optString("error").take(120)}"
        val candidates = root.optJSONArray("candidates")?.let(::names).orEmpty()
        val fromChallenge = root.optString("fromChallenge")
        val picked = root.optString("picked")
        return buildString {
            if (picked.isNotEmpty()) {
                append("машина проверки найдена: $picked")
                if (candidates.isNotEmpty()) append(" (поля: ${candidates.first().take(90)})")
            } else {
                append("машина проверки пока не найдена")
            }
            if (fromChallenge.isNotEmpty()) append(", из проверки: $fromChallenge")
        }
    }

    /**
     * Шаг 7: прогон машины проверки и снимок (заявка на токен целостности).
     *
     * Работает в странице самостоятельно: находит машину, запускает её с программой
     * из проверки, ждёт четыре функции и делает снимок без привязки.
     */
    fun step7Script(): String = """
        (function(){
          function put(obj) { try { window.__oberegStep7 = JSON.stringify(obj); } catch (e) { } }
          put({ stage: 'старт' });
          try {
            var name = window.__oberegVmName;
            var vm = name ? window[name] : null;
            if (!vm || typeof vm.a !== 'function') {
              var names = Object.getOwnPropertyNames(window);
              for (var i = 0; i < names.length; i++) {
                var key = names[i];
                if (key.indexOf('__obereg') === 0) { continue; }
                var value;
                try { value = window[key]; } catch (e2) { continue; }
                if (value && typeof value === 'object' && typeof value.a === 'function') { name = key; vm = value; break; }
              }
            }
            if (!vm || typeof vm.a !== 'function') { put({ stage: 'без-машины' }); return 'started'; }
            window.__oberegVmName = name;
            var root = {};
            try { root = JSON.parse(window.__oberegLastChallenge || '{}'); } catch (e3) { }
            var botguard = root.botguardData || {};
            var program = botguard.program;
            if (typeof program !== 'string' || program.length < 100) { put({ stage: 'без-программы' }); return 'started'; }
            window.__oberegVmReady = false;
            window.__oberegVmSnap = null;
            window.__oberegVmSync = null;
            window.__oberegSnapshot = '';
            window.__oberegSignalOut = [];
            var setup = function(snap, shutdown, pass, camera) {
              window.__oberegVmFns = { snap: typeof snap, shutdown: typeof shutdown, pass: typeof pass, camera: typeof camera };
              if (typeof snap === 'function') { window.__oberegVmSnap = snap; }
              window.__oberegVmReady = true;
            };
            var telemetry = function() { };
            var loggers = [function() { }, function() { }, function() { }, function() { }, function() { }];
            var outcome = null;
            try {
              outcome = vm.a(program, setup, true, undefined, telemetry, [[], []], undefined, false, loggers);
            } catch (e4) {
              put({ stage: 'сбой-запуска', error: String(e4).slice(0, 140) });
              return 'started';
            }
            if (outcome && typeof outcome.then === 'function') {
              outcome.then(function(result) {
                if (result && result.length > 0 && typeof result[0] === 'function') { window.__oberegVmSync = result[0]; }
              }, function(error) { window.__oberegVmError = String(error).slice(0, 140); });
            } else if (outcome && outcome.length > 0 && typeof outcome[0] === 'function') {
              window.__oberegVmSync = outcome[0];
            }
            put({ stage: 'запущена', name: name, program: program.length });
            var startSnapshot = function() {
              window.__oberegSnapshot = '';
              var done = function(value) {
                var text = String(value);
                window.__oberegSnapshot = text;
                put({
                  stage: 'снимок', len: text.length, head: text.slice(0, 90),
                  signals: window.__oberegSignalOut.length,
                  fns: JSON.stringify(window.__oberegVmFns)
                });
              };
              try {
                window.__oberegVmSnap(done, [undefined, undefined, window.__oberegSignalOut, undefined]);
              } catch (e5) {
                if (typeof window.__oberegVmSync === 'function') {
                  try {
                    var direct = window.__oberegVmSync([undefined, undefined, window.__oberegSignalOut, undefined]);
                    done(direct);
                    return;
                  } catch (e6) { put({ stage: 'сбой-снимка', error: String(e6).slice(0, 140) }); return; }
                }
                put({ stage: 'сбой-снимка', error: String(e5).slice(0, 140) });
                return;
              }
              setTimeout(function() {
                if (window.__oberegSnapshot) {
                  put({ stage: 'снимок', len: window.__oberegSnapshot.length, head: window.__oberegSnapshot.slice(0, 90), signals: window.__oberegSignalOut.length });
                } else {
                  put({ stage: 'снимок-молчит', fns: JSON.stringify(window.__oberegVmFns || {}) });
                }
              }, 6000);
            };
            var attempts = 0;
            var waitSetup = function() {
              attempts++;
              if (window.__oberegVmReady) { put({ stage: 'готова', fns: JSON.stringify(window.__oberegVmFns) }); startSnapshot(); return; }
              if (window.__oberegVmError) { put({ stage: 'сбой-машины', error: window.__oberegVmError }); return; }
              if (attempts > 20) { put({ stage: 'нет-функций', fns: JSON.stringify(window.__oberegVmFns || {}) }); return; }
              setTimeout(waitSetup, 300);
            };
            waitSetup();
            return 'started';
          } catch (e) {
            put({ stage: 'сбой', error: String(e).slice(0, 140) });
            return 'started';
          }
        })()
    """.trimIndent()

    /** Опрос состояния шага 7. */
    fun step7PollScript(): String =
        "(window.__oberegStep7 || '') + '||' + (window.__oberegStages || '')"

    /** Этап шага 7 из ответа страницы. */
    fun step7Stage(jsonText: String?): String {
        val root = runCatching { JSONObject(jsonText.orEmpty()) }.getOrNull() ?: return ""
        return root.optString("stage")
    }

    /** Финальные этапы прогона (промежуточные — ждём дальше). */
    fun step7Final(stage: String): Boolean = stage in setOf(
        "снимок", "сбой", "сбой-запуска", "сбой-машины", "сбой-снимка",
        "нет-функций", "снимок-молчит", "без-машины", "без-программы",
    )

    /** Сводка шага 7. */
    fun summarizeStep7(jsonText: String?): String {
        if (jsonText.isNullOrEmpty()) return "прогон проверки: движок не ответил"
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return "прогон проверки не прочитан: ${jsonText.take(120)}"
        return when (val stage = root.optString("stage")) {
            "снимок" -> "снимок получен (${root.optInt("len")} символов), сигналов: ${root.optInt("signals")}; начало: ${root.optString("head").take(70)}"
            "запущена" -> "машина проверки запущена (имя: ${root.optString("name")}, программа: ${root.optInt("program")} символов)"
            "готова" -> "машина проверки отдала функции: ${root.optString("fns").take(140)}"
            "без-машины" -> "машина проверки не найдена — сначала шаг 6"
            "без-программы" -> "программы нет — сначала шаги 2 и 3"
            "сбой", "сбой-запуска", "сбой-машины", "сбой-снимка" -> "сбой: ${root.optString("error").take(140)}"
            "нет-функций" -> "машина не отдала функции: ${root.optString("fns").take(120)}"
            "снимок-молчит" -> "снимок не ответил за 6 секунд (функции: ${root.optString("fns").take(100)})"
            else -> "прогон проверки: этап \"$stage\""
        }
    }

    /** Публичный ключ WAA-обмена (как в эталонном клиенте). */
    const val WAA_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"

    /** Пробное видео для цепочки проверки. */
    const val PROBE_VIDEO_ID = "dQw4w9WgXcQ"

    /** Разобранный ответ обмена на токен целостности. */
    data class IntegrityTokenInfo(
        val token: String,
        val ttlSecs: Int,
        val refreshSecs: Int,
        val fallback: String,
    )

    /** Разбор ответа обмена: [токен, срок, порог обновления, запасной токен]. */
    fun parseIntegrityResponse(jsonText: String?): IntegrityTokenInfo? {
        if (jsonText.isNullOrEmpty() || !jsonText.trimStart().startsWith("[")) return null
        val arr = runCatching { org.json.JSONArray(jsonText) }.getOrNull() ?: return null
        val token = arr.optString(0)
        if (token.length < 20) return null
        return IntegrityTokenInfo(token, arr.optInt(1, 0), arr.optInt(2, 0), arr.optString(3))
    }

    /**
     * Шаг 8: минт PO-токена в странице.
     *
     * Ждёт от машины проверки функцию-минтер, отдаёт ей токен целостности
     * и получает готовый PO-токен для выбранного видео.
     */
    fun mintScript(): String = """
        (function(){
          function put(obj) { try { window.__oberegPoToken = JSON.stringify(obj); } catch (e) { } }
          put({ stage: 'старт' });
          try {
            var token = window.__oberegIntegrityToken;
            if (typeof token !== 'string' || token.length < 10) { put({ stage: 'без-токена' }); return 'started'; }
            function b64ToU8(base64) {
              if (base64.indexOf('-') >= 0 || base64.indexOf('_') >= 0 || base64.indexOf('.') >= 0) {
                base64 = base64.split('-').join('+').split('_').join('/').split('.').join('=');
              }
              var raw = atob(base64);
              var out = new Uint8Array(raw.length);
              for (var i = 0; i < raw.length; i++) { out[i] = raw.charCodeAt(i); }
              return out;
            }
            function u8ToB64Url(u8) {
              var s = '';
              for (var i = 0; i < u8.length; i++) { s += String.fromCharCode(u8[i]); }
              return btoa(s).split('+').join('-').split('/').join('_');
            }
            var videoId = window.__oberegPoVideo || 'dQw4w9WgXcQ';
            var attempts = 0;
            var waitMinter = function() {
              attempts++;
              var out = window.__oberegSignalOut || [];
              var getMinter = out[0];
              if (typeof getMinter !== 'function') {
                if (attempts > 20) { put({ stage: 'нет-минтера', signals: out.length }); return; }
                setTimeout(waitMinter, 300);
                return;
              }
              put({ stage: 'минтер-есть' });
              var p = null;
              try { p = getMinter(b64ToU8(token)); } catch (e1) { put({ stage: 'сбой-минтера', error: String(e1).slice(0, 140) }); return; }
              Promise.resolve(p).then(function(mintCallback) {
                if (typeof mintCallback !== 'function') { put({ stage: 'минтер-молчит' }); return; }
                var r = null;
                try { r = mintCallback(new TextEncoder().encode(videoId)); } catch (e2) { put({ stage: 'сбой-минта', error: String(e2).slice(0, 140) }); return; }
                Promise.resolve(r).then(function(bytes) {
                  if (!bytes || !bytes.length) { put({ stage: 'пустой-токен' }); return; }
                  var poToken = u8ToB64Url(bytes);
                  window.__oberegPoTokenValue = poToken;
                  put({ stage: 'по-токен', video: videoId, len: poToken.length, token: poToken, head: poToken.slice(0, 60) });
                }, function(e3) { put({ stage: 'сбой-минта', error: String(e3).slice(0, 140) }); });
              }, function(e4) { put({ stage: 'сбой-минтера', error: String(e4).slice(0, 140) }); });
            };
            waitMinter();
            return 'started';
          } catch (e) {
            put({ stage: 'сбой', error: String(e).slice(0, 140) });
            return 'started';
          }
        })()
    """.trimIndent()

    /** Опрос состояния минта. */
    fun mintPollScript(): String =
        "(window.__oberegPoToken || '') + '||' + (window.__oberegStages || '')"

    /** Этап минта. */
    fun mintStage(jsonText: String?): String =
        runCatching { JSONObject(jsonText.orEmpty()).optString("stage") }.getOrDefault("")

    /** Финальные этапы минта. */
    fun mintFinal(stage: String): Boolean = stage in setOf(
        "по-токен", "сбой", "сбой-минтера", "сбой-минта", "нет-минтера", "минтер-молчит",
        "пустой-токен", "без-токена",
    )

    /** Значение PO-токена из ответа страницы. */
    fun mintValue(jsonText: String?): String? {
        val root = runCatching { JSONObject(jsonText.orEmpty()) }.getOrNull() ?: return null
        return if (root.optString("stage") == "по-токен") {
            root.optString("token").takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }

    /** Сводка минта. */
    fun summarizeMint(jsonText: String?): String {
        if (jsonText.isNullOrEmpty()) return "минт: движок не ответил"
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return "минт не прочитан: ${jsonText.take(120)}"
        return when (val stage = root.optString("stage")) {
            "по-токен" -> "PO-токен получен (${root.optInt("len")} символов) для ${root.optString("video")}; начало: ${root.optString("head").take(50)}"
            "минтер-есть" -> "машина проверки отдала минтер"
            "нет-минтера" -> "минтер не появился (сигналов: ${root.optInt("signals")})"
            "без-токена" -> "токена целостности нет — сначала шаг 8"
            "сбой", "сбой-минтера", "сбой-минта" -> "сбой минта: ${root.optString("error").take(140)}"
            else -> "минт: этап \"$stage\""
        }
    }

    /** Приводит адрес к полному виду: адрес без схемы (//host/…) → https. */
    fun normalizeInterpreterUrl(raw: String): String {
        if (raw.isEmpty() || raw == "null") return ""
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> "https://www.youtube.com$raw"
            else -> raw
        }
    }

    private fun names(array: org.json.JSONArray): List<String> =
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { name -> name.isNotEmpty() } }
}