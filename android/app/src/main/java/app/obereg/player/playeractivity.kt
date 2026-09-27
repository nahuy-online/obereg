package app.obereg.player

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Минимальный плеер: воспроизведение видеофайла, при включённой галочке —
 * через локальный SOCKS5-прокси ядра (то есть через встроенный туннель).
 *
 * Что уже есть: воспроизведение произвольной ссылки на видео, маршрутизация через туннель,
 * отображение состояния и счётчика обслуженных соединений.
 *
 * Что относится к следующему этапу продукта: каталог и поиск видео, извлечение потоков
 * у видеосервиса, фильтрация рекламных вставок, скачивание и офлайн.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var urlInput: EditText
    private lateinit var tunnelCheck: CheckBox
    private lateinit var statusView: TextView
    private var currentTitle: String = ""
    private var lastPlayerEvent: String = "событий ещё не было"

    private val presets = listOf(
        "asset:///sample.mp4",
        "https://download.samplelib.com/mp4/sample-5s.mp4",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        urlInput = findViewById(R.id.urlInput)
        tunnelCheck = findViewById(R.id.checkTunnel)
        statusView = findViewById(R.id.playerStatus)

        urlInput.setText(presets.first())

        findViewById<Button>(R.id.btnPlay).setOnClickListener { play(urlInput.text.toString().trim()) }
        findViewById<Button>(R.id.btnStopPlayback).setOnClickListener { stopPlayback() }
        findViewById<Button>(R.id.btnPreset1).setOnClickListener {
            urlInput.setText(presets[0])
            play(presets[0])
        }
        findViewById<Button>(R.id.btnPreset2).setOnClickListener {
            urlInput.setText(presets[1])
            play(presets[1])
        }
        tunnelCheck.setOnCheckedChangeListener { _, checked ->
            renderStatus(if (checked) "маршрут: туннель (применяется при следующем запуске)" else "маршрут: напрямую")
        }

        renderStatus("готово к воспроизведению")

        // Запуск из каталога: ссылка и название приходят во вложениях.
        val incomingUrl = intent?.getStringExtra(EXTRA_URL)
        if (!incomingUrl.isNullOrEmpty()) {
            currentTitle = intent?.getStringExtra(EXTRA_TITLE) ?: ""
            urlInput.setText(incomingUrl)
            play(incomingUrl)
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun play(url: String) {
        if (url.isEmpty()) {
            renderStatus("укажите ссылку на видеофайл")
            return
        }
        val local = url.startsWith("asset:///")
        val tunnel = tunnelCheck.isChecked && !local
        val proxyAddress = if (tunnel) CoreHolder.status().listenAddr else null

        if (tunnel && proxyAddress == null) {
            renderStatus("туннель не поднят — на экране приёмки нажмите «Запустить туннель»; сейчас играю напрямую")
        }

        val exo = player ?: ExoPlayer.Builder(this).build().also {
            player = it
            playerView.player = it
            it.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    lastPlayerEvent = "ОШИБКА ${error.errorCodeName}: ${error.message}"
                    renderStatus("воспроизведение не удалось")
                }

                override fun onPlaybackStateChanged(state: Int) {
                    lastPlayerEvent = when (state) {
                        Player.STATE_IDLE -> "idle"
                        Player.STATE_BUFFERING -> "буферизация"
                        Player.STATE_READY -> "готово/играет"
                        Player.STATE_ENDED -> "завершено"
                        else -> "состояние $state"
                    }
                    renderStatus("состояние воспроизведения обновлено")
                }
            })
        }
        exo.stop()
        exo.clearMediaItems()

        val mediaSource = buildMediaSource(url, proxyAddress)
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = true

        val route = when {
            local -> "локальный файл внутри приложения"
            proxyAddress != null -> "через туннель ($proxyAddress)"
            else -> "напрямую"
        }
        val titleLine = if (currentTitle.isEmpty()) "" else "$currentTitle\n"
        renderStatus("${titleLine}воспроизведение: $route\nисточник: $url")
    }

    private fun stopPlayback() {
        player?.stop()
        player?.clearMediaItems()
        renderStatus("остановлено")
    }

    private fun buildMediaSource(url: String, proxyAddress: String?) =
        ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this, httpDataSource(proxyAddress)))
            .createMediaSource(MediaItem.fromUri(url))

    /** Сетевой слой плеера: при наличии адреса прокси HTTP-трафик идёт в SOCKS5 ядра. */
    private fun httpDataSource(proxyAddress: String?): OkHttpDataSource.Factory {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(20))
        if (proxyAddress != null) {
            val parts = proxyAddress.split(":")
            if (parts.size == 2) {
                clientBuilder.proxy(
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(parts[0], parts[1].toInt())),
                )
            }
        }
        return OkHttpDataSource.Factory(clientBuilder.build())
    }

    private fun renderStatus(message: String) {
        val status = runCatching { CoreHolder.status() }.getOrNull()
        val core = buildString {
            appendLine(message)
            appendLine("плеер: $lastPlayerEvent")
            if (status != null) {
                appendLine("ядро: ${status.state} / ${status.mode} / прокси ${status.listenAddr ?: "нет"}")
                append("обслужено соединений: ${status.sessionsServed}, ↓${status.bytesDown} Б")
            }
        }
        statusView.text = core
    }

    companion object {
        const val EXTRA_URL = "obereg.url"
        const val EXTRA_TITLE = "obereg.title"
    }
}
