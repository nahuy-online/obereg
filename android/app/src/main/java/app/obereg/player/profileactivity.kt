package app.obereg.player

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.appcompat.app.AppCompatActivity

/**
 * Профиль и настройки.
 *
 * Здесь живёт выключатель нашего туннеля (как и просил пользователь), выбор маршрута
 * для экрана сервиса и наша рекомендация для ленты: какой ролик и на какую позицию
 * подставлять.
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var tunnelCheck: CheckBox
    private lateinit var screenCheck: CheckBox
    private lateinit var promoId: EditText
    private lateinit var promoTitle: EditText
    private lateinit var promoPosition: EditText
    private lateinit var statusView: TextView
    private lateinit var tokenWebView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        tunnelCheck = findViewById(R.id.checkTunnelSetting)
        screenCheck = findViewById(R.id.checkScreenTunnel)
        promoId = findViewById(R.id.promoIdInput)
        promoTitle = findViewById(R.id.promoTitleInput)
        promoPosition = findViewById(R.id.promoPositionInput)
        statusView = findViewById(R.id.profileStatus)
        tokenWebView = findViewById(R.id.tokenWebView)
        tokenWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        tokenWebView.webChromeClient = WebChromeClient()
        tokenWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                runTokenProbe(attempt = 1)
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                val description = error?.description?.toString() ?: "неизвестная ошибка"
                if (request?.isForMainFrame == true) {
                    DiagLog.add("токены: страница не загрузилась — $description")
                    render("страница сервиса не загрузилась: $description")
                }
            }
        }

        tunnelCheck.isChecked = AppSettings.tunnelDesired(this)
        screenCheck.isChecked = AppSettings.screenViaTunnel(this)
        promoId.setText(AppSettings.promoVideoId(this))
        promoTitle.setText(AppSettings.promoTitle(this))
        promoPosition.setText(AppSettings.promoPosition(this).toString())

        tunnelCheck.setOnCheckedChangeListener { _, checked ->
            AppSettings.setTunnelDesired(this, checked)
            if (checked) {
                TunnelService.start(this)
                render("туннель: включаю (поднимите его готовность — статус ниже)")
            } else {
                TunnelService.stop(this)
                render("туннель: выключаю")
            }
        }

        screenCheck.setOnCheckedChangeListener { _, checked ->
            AppSettings.setScreenViaTunnel(this, checked)
            render(
                if (checked) {
                    "экран сервиса пойдёт через наш туннель"
                } else {
                    "экран сервиса пойдёт напрямую (системный VPN, если включён)"
                },
            )
        }

        findViewById<Button>(R.id.btnTokenProbe).setOnClickListener {
            render("разведка движка токенов: открываю страницу сервиса…")
            DiagLog.add("токены: запуск разведки")
            tokenWebView.loadUrl(TokenProbe.PROBE_URL)
        }
        findViewById<Button>(R.id.btnChallengeProbe).setOnClickListener {
            // Шаг 2: страница сама обращается к сервису за проверкой, мы читаем ответ.
            render("запрос проверки: обращаюсь из движка…")
            DiagLog.add("токены: запрос проверки")
            tokenWebView.evaluateJavascript(TokenProbe.challengeScript()) { _ -> }
            pollChallenge(1)
        }
        findViewById<Button>(R.id.btnChallengeDetail).setOnClickListener {
            render("разбор проверки: обращаюсь из движка…")
            DiagLog.add("токены: разбор проверки")
            tokenWebView.evaluateJavascript(TokenProbe.challengeDetailScript()) { _ -> }
            pollChallengeDetail(1)
        }
        findViewById<Button>(R.id.btnVmRun).setOnClickListener {
            render("прогон проверки: запускаю машину:")
            DiagLog.add("токены: прогон проверки")
            tokenWebView.evaluateJavascript(TokenProbe.step7Script()) { _ -> }
            pollStep7(1)
        }
        findViewById<Button>(R.id.btnTokenExchange).setOnClickListener {
            runTokenExchange()
        }
        findViewById<Button>(R.id.btnInterpreterLoad).setOnClickListener {
            render("ищу адрес интерпретатора…")
            DiagLog.add("токены: поиск интерпретатора")
            tokenWebView.evaluateJavascript(TokenProbe.interpreterFindScript()) { raw ->
                val summary = TokenProbe.summarizeInterpreterFind(PlayerApi.decodeJsString(raw))
                DiagLog.add("токены: $summary")
                render(summary)
                val root = runCatching {
                    org.json.JSONObject(PlayerApi.decodeJsString(raw).orEmpty())
                }.getOrNull()
                val urls = root?.optJSONArray("urls")
                var url = ""
                if (urls != null && urls.length() > 0) { url = urls.optString(0) }
                url = TokenProbe.normalizeInterpreterUrl(url)
                if (url.isEmpty()) {
                    DiagLog.add("токены: адрес интерпретатора не найден")
                    return@evaluateJavascript
                }
                Thread {
                    var failure = ""
                    val text = try {
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(java.time.Duration.ofSeconds(15))
                            .readTimeout(java.time.Duration.ofSeconds(25))
                            .build()
                        val request = okhttp3.Request.Builder().url(url).build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) response.body?.string() else null
                        }
                    } catch (error: Exception) {
                        failure = error.message.orEmpty()
                        null
                    }
                    runOnUiThread {
                        if (text.isNullOrEmpty()) {
                            DiagLog.add("токены: интерпретатор не получен (сеть): ${failure.take(110)}")
                            render("интерпретатор не получен (сеть): ${failure.take(110)}")
                            return@runOnUiThread
                        }
                        val head = text.replace("\n", " ").take(160)
                        DiagLog.add("токены: интерпретатор получен (${text.length} символов): $head")
                        // Снимок имён до вложения — потом сравним, что принесла машина проверки.
                        tokenWebView.evaluateJavascript(TokenProbe.armErrorsScript()) { _ -> }
                        tokenWebView.evaluateJavascript(TokenProbe.globalsSnapshotScript()) { _ -> }
                        val escaped = org.json.JSONObject.quote(text)
                        tokenWebView.evaluateJavascript(
                            "window.__oberegInterpreterText = $escaped; 'ok'",
                        ) { _ ->
                            tokenWebView.evaluateJavascript(text) { _ ->
                                tokenWebView.evaluateJavascript(TokenProbe.programCheckScript()) { checkRaw ->
                                    val check = TokenProbe.summarizeProgramCheck(
                                        PlayerApi.decodeJsString(checkRaw),
                                    )
                                    DiagLog.add("токены: интерпретатор вложен, $check")
                                    tokenWebView.evaluateJavascript(TokenProbe.vmFindScript()) { vmRaw ->
                                        val vmSummary = TokenProbe.summarizeVmFind(PlayerApi.decodeJsString(vmRaw))
                                        DiagLog.add("токены: $vmSummary")
                                        tokenWebView.evaluateJavascript(TokenProbe.globalsSnapshotScript()) { diffRaw ->
                                            val diff = TokenProbe.summarizeGlobalsDiff(PlayerApi.decodeJsString(diffRaw))
                                            DiagLog.add("токены: $diff")
                                            tokenWebView.evaluateJavascript(TokenProbe.errorsReportScript()) { errRaw ->
                                                val errs = TokenProbe.summarizeErrors(PlayerApi.decodeJsString(errRaw))
                                                if (errs.isNotEmpty()) DiagLog.add("токены: $errs")
                                                val tail = if (errs.isEmpty()) "" else "; $errs"
                                                render("интерпретатор: ${text.length} символов; $check; $vmSummary; $diff$tail")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.start()
            }
        }
        findViewById<Button>(R.id.btnIntrospect).setOnClickListener {
            render("разбор программы проверки…")
            DiagLog.add("токены: разбор программы")
            tokenWebView.evaluateJavascript(TokenProbe.programIntrospectScript()) { raw ->
                val summary = TokenProbe.summarizeIntrospect(PlayerApi.decodeJsString(raw))
                DiagLog.add("токены: $summary")
                render("разбор программы: $summary")
                loadInterpreterIfNeeded()
            }
        }
        findViewById<Button>(R.id.btnProgramLoad).setOnClickListener {
            // Программу вкладывает приложение: страница сервиса запрещает eval (Trusted Types).
            render("вкладываю программу проверки в движок…")
            DiagLog.add("токены: вложение программы")
            tokenWebView.evaluateJavascript(TokenProbe.programExtractScript()) { raw ->
                val program = PlayerApi.decodeJsString(raw)
                if (program.isNullOrEmpty()) {
                    DiagLog.add("токены: программы нет — сначала шаг 3")
                    render("программы нет в сохранённой проверке — сначала шаг 3")
                    return@evaluateJavascript
                }
                DiagLog.add("токены: программа получена (${program.length} символов)")
                tokenWebView.evaluateJavascript(program) { _ ->
                    tokenWebView.evaluateJavascript(TokenProbe.programCheckScript()) { checkRaw ->
                        val summary = TokenProbe.summarizeProgramCheck(PlayerApi.decodeJsString(checkRaw))
                        DiagLog.add("токены: $summary")
                        render("вложение программы: $summary")
                    }
                }
            }
        }
        findViewById<Button>(R.id.btnProfileReport).setOnClickListener { copyReport() }
        findViewById<Button>(R.id.btnSavePromo).setOnClickListener { savePromo() }
        findViewById<Button>(R.id.btnProfileBack).setOnClickListener { finish() }

        render("настройки: изменения сохраняются сразу")
    }

    private fun savePromo() {
        val id = promoId.text.toString().trim()
        val title = promoTitle.text.toString().trim()
        val position = SettingsCodec.clampPosition(promoPosition.text.toString())
        promoPosition.setText(position.toString())
        if (id.isNotEmpty() && !SettingsCodec.looksLikeVideoId(id)) {
            render("наш ролик: идентификатор должен быть 11 символов (как в ссылке видео); пока не сохранил")
            return
        }
        AppSettings.setPromo(this, id, title, position)
        render(
            if (id.isEmpty()) {
                "наша рекомендация выключена: ролик не подставляется"
            } else {
                "наша рекомендация: $id на позицию $position"
            },
        )
    }

    /**
     * Подгрузка интерпретатора проверки: адрес приходит вместе с проверкой,
     * а забирает его приложение — страница этого сделать не может (запрет eval).
     */
    private fun loadInterpreterIfNeeded() {
        tokenWebView.evaluateJavascript(TokenProbe.programIntrospectScript()) { raw ->
            val root = runCatching {
                org.json.JSONObject(PlayerApi.decodeJsString(raw).orEmpty())
            }.getOrNull() ?: return@evaluateJavascript
            val url = TokenProbe.normalizeInterpreterUrl(root.optString("interpreterUrl"))
            if (url.isEmpty() || url == "null") {
                DiagLog.add("токены: адреса интерпретатора нет в проверке")
                return@evaluateJavascript
            }
            if (root.optBoolean("hasInterpreterNow")) {
                DiagLog.add("токены: интерпретатор уже загружен")
                return@evaluateJavascript
            }
            Thread {
                var failure2 = ""
                val text = try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(java.time.Duration.ofSeconds(15))
                        .readTimeout(java.time.Duration.ofSeconds(20))
                        .build()
                    val request = okhttp3.Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) response.body?.string() else null
                    }
                } catch (error: Exception) {
                    failure2 = error.message.orEmpty()
                    null
                }
                runOnUiThread {
                    if (text.isNullOrEmpty()) {
                        DiagLog.add("токены: интерпретатор не получен: ${failure2.take(110)}")
                        render("интерпретатор не получен (сеть)")
                        return@runOnUiThread
                    }
                    DiagLog.add("токены: интерпретатор получен (${text.length} символов)")
                    // Складываем текст в страницу, чтобы им можно было воспользоваться дальше.
                    val escaped = org.json.JSONObject.quote(text)
                    tokenWebView.evaluateJavascript(
                        "window.__oberegInterpreterText = $escaped; 'ok'",
                    ) { _ ->
                        tokenWebView.evaluateJavascript(text) { _ ->
                            tokenWebView.evaluateJavascript(TokenProbe.programCheckScript()) { checkRaw ->
                                val check = TokenProbe.summarizeProgramCheck(PlayerApi.decodeJsString(checkRaw))
                                DiagLog.add("токены: интерпретатор вложен, $check")
                                render("интерпретатор вложен: $check")
                            }
                        }
                    }
                }
            }.start()
        }
    }
    /** Обмен снимка на токен целостности (шаг 8) и запуск минта PO-токена. */
    private fun runTokenExchange() {
        render("обмен на токен целостности:")
        DiagLog.add("токены: обмен на токен целостности")
        tokenWebView.evaluateJavascript("(window.__oberegSnapshot || '')") { raw ->
            val snapshot = PlayerApi.decodeJsString(raw)
            if (snapshot.isNullOrEmpty() || snapshot.length < 50) {
                DiagLog.add("токены: снимка нет - сначала шаг 7")
                render("снимка нет — сначала шаг 7")
                return@evaluateJavascript
            }
            Thread {
                var failure = ""
                var resultText = ""
                var httpCode = 0
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(java.time.Duration.ofSeconds(15))
                        .readTimeout(java.time.Duration.ofSeconds(25))
                        .build()
                    val bodyJson = org.json.JSONArray()
                        .put("O43z0dpjhgX20SCx4KAo")
                        .put(snapshot)
                        .toString()
                    val body = bodyJson.toRequestBody("application/json+protobuf".toMediaType())
                    val request = okhttp3.Request.Builder()
                        .url("https://www.youtube.com/api/jnn/v1/GenerateIT")
                        .post(body)
                        .header("content-type", "application/json+protobuf")
                        .header("x-goog-api-key", TokenProbe.WAA_API_KEY)
                        .header("x-user-agent", "grpc-web-javascript/0.1")
                        .build()
                    client.newCall(request).execute().use { response ->
                        httpCode = response.code
                        resultText = response.body?.string().orEmpty()
                    }
                } catch (error: Exception) {
                    failure = error.message.orEmpty()
                }
                runOnUiThread {
                    if (failure.isNotEmpty()) {
                        DiagLog.add("токены: обмен не прошёл: ${failure.take(110)}")
                        render("обмен не прошёл: ${failure.take(110)}")
                        return@runOnUiThread
                    }
                    val info = TokenProbe.parseIntegrityResponse(resultText)
                    if (info == null) {
                        DiagLog.add("токены: обмен: HTTP $httpCode, ответ: ${resultText.take(140)}")
                        render("обмен: HTTP $httpCode, ответ: ${resultText.take(140)}")
                        return@runOnUiThread
                    }
                    DiagLog.add(
                        "токены: токен целостности получен (${info.token.length} символов), срок ${info.ttlSecs} сек, порог ${info.refreshSecs} сек",
                    )
                    val escaped = org.json.JSONObject.quote(info.token)
                    tokenWebView.evaluateJavascript("window.__oberegIntegrityToken = $escaped; 'ok'") { _ ->
                        tokenWebView.evaluateJavascript(TokenProbe.mintScript()) { _ -> }
                    }
                    pollMint(1)
                }
            }.start()
        }
    }

    /** Минт PO-токена: ждём результат из страницы и сохраняем токен для видео. */
    private fun pollMint(attempt: Int) {
        tokenWebView.postDelayed({
            tokenWebView.evaluateJavascript(TokenProbe.mintPollScript()) { raw ->
                val (result, stages) = TokenProbe.splitPoll(raw)
                if (result.isEmpty()) {
                    if (attempt < 15) {
                        pollMint(attempt + 1)
                    } else {
                        DiagLog.add("токены: минт - ответа нет (этапы: $stages)")
                        render("минт: ответа нет")
                    }
                    return@evaluateJavascript
                }
                val stage = TokenProbe.mintStage(result)
                val summary = TokenProbe.summarizeMint(result)
                if (TokenProbe.mintFinal(stage)) {
                    DiagLog.add("токены: $summary")
                    render(summary)
                    val token = TokenProbe.mintValue(result)
                    if (token != null) {
                        TokenStore.put(
                            TokenProbe.PROBE_VIDEO_ID,
                            PoToken(token, System.currentTimeMillis() + 6 * 60 * 60 * 1000L),
                        )
                        DiagLog.add("токены: PO-токен сохранён для ${TokenProbe.PROBE_VIDEO_ID}")
                    }
                } else {
                    render("минт: $summary")
                    if (attempt < 15) {
                        pollMint(attempt + 1)
                    } else {
                        DiagLog.add("токены: минт затянулся: $summary")
                    }
                }
            }
        }, 1_000)
    }

    /** Прогон машины проверки (шаг 7): ждём снимок из страницы. */
    private fun pollStep7(attempt: Int) {
        tokenWebView.postDelayed({
            tokenWebView.evaluateJavascript(TokenProbe.step7PollScript()) { raw ->
                val (result, stages) = TokenProbe.splitPoll(raw)
                if (result.isEmpty()) {
                    if (attempt < 12) {
                        pollStep7(attempt + 1)
                    } else {
                        DiagLog.add("токены: прогон проверки - ответа нет (этапы: $stages)")
                        render("прогон проверки: ответа нет")
                    }
                    return@evaluateJavascript
                }
                val stage = TokenProbe.step7Stage(result)
                val summary = TokenProbe.summarizeStep7(result)
                if (TokenProbe.step7Final(stage)) {
                    DiagLog.add("токены: $summary")
                    render(summary)
                } else {
                    render("прогон проверки: $summary")
                    if (attempt < 15) {
                        pollStep7(attempt + 1)
                    } else {
                        DiagLog.add("токены: прогон проверки затянулся: $summary")
                        render("прогон проверки затянулся: $summary")
                    }
                }
            }
        }, 1_000)
    }

    /** Опрос разбора проверки (шаг 3) — тем же способом, что и сам запрос. */
    private fun pollChallengeDetail(attempt: Int) {
        tokenWebView.postDelayed({
            tokenWebView.evaluateJavascript(TokenProbe.challengePollScript()) { raw ->
                val (result, stages) = TokenProbe.splitPoll(raw)
                if (result.isEmpty()) {
                    if (attempt < 12) {
                        pollChallengeDetail(attempt + 1)
                    } else {
                        val tail = if (stages.isEmpty()) "этапов нет" else "этапы: $stages"
                        DiagLog.add("токены: разбор проверки — ответа нет ($tail)")
                        render("разбор проверки: ответа нет — $tail")
                    }
                    return@evaluateJavascript
                }
                val summary = TokenProbe.summarizeChallengeDetail(result)
                DiagLog.add("токены: $summary")
                render("разбор проверки: $summary")
            }
        }, 1_000)
    }
    /** Опрос результата проверки: движок не ждёт сетевых вызовов, поэтому спрашиваем повторно. */
    private fun pollChallenge(attempt: Int) {
        tokenWebView.postDelayed({
            tokenWebView.evaluateJavascript(TokenProbe.challengePollScript()) { raw ->
                val (result, stages) = TokenProbe.splitPoll(raw)
                if (result.isEmpty()) {
                    if (attempt < 12) {
                        pollChallenge(attempt + 1)
                    } else {
                        val tail = if (stages.isEmpty()) "этапов нет" else "этапы: $stages"
                        DiagLog.add("токены: запрос проверки — ответа нет ($tail)")
                        render("запрос проверки: ответа нет за 12 секунд — $tail")
                    }
                    return@evaluateJavascript
                }
                val summary = TokenProbe.summarizeChallenge(result)
                DiagLog.add("токены: $summary")
                render("запрос проверки: $summary")
            }
        }, 1_000)
    }
    /** Копия отчёта с профиля: тот же общий журнал, что на других экранах. */
    private fun copyReport() {
        val core = runCatching { CoreHolder.status() }.getOrNull()
        val header = buildString {
            appendLine("Obereg · отчёт диагностики (профиль)")
            appendLine(
                "время: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
                    .format(java.util.Date()),
            )
            // Идентификация сборки: без неё невозможно понять, какая версия отвечает в отчёте.
            appendLine("сборка: " + buildLabel())
            appendLine("туннель: ${if (tunnelCheck.isChecked) "включён" else "выключен"}")
            if (core != null) {
                appendLine("ядро: ${core.state} / ${core.mode} / прокси ${core.listenAddr ?: "нет"}")
            }
            appendLine("--- журнал ---")
        }
        val report = header + DiagLog.dump()
        runCatching {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Obereg отчёт", report))
        }
        DiagLog.add("отчёт скопирован (${report.length} символов)")
        render("отчёт скопирован (${report.length} символов) — вставьте его в чат")
    }

    /** Сборка: версия, время сборки и время установки — по ним видно, какая версия работает. */
    private fun buildLabel(): String {
        val version = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${info.lastUpdateTime})"
        }.getOrNull() ?: "неизвестно"
        return "$version, установлена: " + java.text.SimpleDateFormat(
            "dd.MM HH:mm",
            java.util.Locale.ROOT,
        ).format(java.util.Date(runCatching {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrDefault(0L)))
    }

    /**
     * Разведка движка: что страница сервиса отдаёт нашей WebView.
     *
     * Результат попадает и на экран, и в журнал — по нему решается следующий шаг этапа.
     */
    private fun runTokenProbe(attempt: Int) {
        tokenWebView.evaluateJavascript(TokenProbe.reportScript()) { raw ->
            val summary = TokenProbe.summarize(raw)
            DiagLog.add("токены: $summary")
            // Пустая страница (нет скриптов) — почти всегда сорванная загрузка:
            // даём ей вторую попытку, прежде чем делать выводы.
            val empty = summary.contains("скриптов на странице: 0")
            if (empty && attempt < 2) {
                render("страница пустая — пробую перезагрузить")
                tokenWebView.postDelayed({ tokenWebView.loadUrl(TokenProbe.PROBE_URL) }, 2_000)
                return@evaluateJavascript
            }
            render("движок токенов: $summary")
        }
    }

    private fun render(message: String) {
        val core = runCatching { CoreHolder.status() }.getOrNull()
        statusView.text = buildString {
            appendLine(message)
            if (core != null) {
                appendLine("ядро: ${core.state} / ${core.mode} / прокси ${core.listenAddr ?: "нет"}")
            }
            append("туннель: ${if (tunnelCheck.isChecked) "включён в настройках" else "выключен в настройках"}")
        }
        findViewById<android.widget.ScrollView>(R.id.profileScroll)?.fullScroll(android.view.View.FOCUS_UP)
    }
}