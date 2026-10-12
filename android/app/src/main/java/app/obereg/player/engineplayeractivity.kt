package app.obereg.player

import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран ролика.
 *
 * Ролик живёт в общем сеансе (PlayerSession), поэтому переключения его не рвут:
 * «‹» и свайп вниз уводят ролик в мини-плеер внизу оболочки (видео продолжает играть),
 * а отдельная кнопка «В окне» отправляет его в системное окно поверх других приложений
 * (в окошке виден только ролик, плюс кнопка play/pause).
 * Скорость — чип «1×» в шапке. Пока идёт «картинка-в-картинке», вся наша шапка скрыта,
 * чтобы в окошке был только ролик.
 */
class EnginePlayerActivity : AppCompatActivity(), PlayerSession.Host, PlayerSession.PipHost {

    private lateinit var holder: FrameLayout
    private lateinit var statusView: TextView
    private lateinit var topBar: View
    private lateinit var speedChip: TextView
    private var videoId = ""
    private var lastCollapseAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_engine_player)
        videoId = intent.getStringExtra("videoId") ?: ""
        val videoTitle = intent.getStringExtra("title") ?: ""
        holder = findViewById(R.id.engineHolder)
        statusView = findViewById(R.id.engineStatus)
        topBar = findViewById(R.id.engineTopBar)
        speedChip = findViewById(R.id.btnEngineSpeed)
        speedChip.text = speedLabel(PlayerSession.speed)
        findViewById<TextView>(R.id.btnEngineBack).setOnClickListener { collapseToMini() }
        speedChip.setOnClickListener { showSettings() }
        findViewById<TextView>(R.id.btnEnginePip).setOnClickListener { enterPip() }
        PlayerSession.host = this
        PlayerSession.lastStatus = { text -> runOnUiThread { statusView.text = text } }
        PlayerSession.open(this, videoId, videoTitle)
        PlayerSession.attachTo(holder)
        requestNotificationPermissionIfNeeded()
    }

    /** Медиа-уведомление: на новых системах нужно разрешение — спросим при первом ролике. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            return
        }
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            DiagLog.add("движок-плеер: спрашиваю разрешение на уведомление")
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PlayerSession.host = this
        PlayerSession.setAppForeground(true)
        if (!PlayerSession.pipActive) {
            PlayerSession.attachTo(holder)
            // Страховка: если вышли из окна без события, вернём обычную вёрстку страницы.
            PlayerSession.applyPipLayout(false)
        }
        PlayerSession.startPlayerWatch(this)
        PlayerSession.maybeRecoverOnReturn(this)
        speedChip.text = speedLabel(PlayerSession.speed)
    }

    override fun onDestroy() {
        if (PlayerSession.host === this) {
            PlayerSession.host = null
        }
        PlayerSession.lastStatus = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        PlayerSession.stopPlayerWatch()
        PlayerSession.setAppForeground(false)
        // Экран уходит в окно или приложение сворачивается — не даём странице заснуть:
        // так ролик играет в «окне» и в фоне, и кнопки play/pause снова работают.
        PlayerSession.keepAlive()
    }

    override fun onMinimizeRequested() {
        runOnUiThread { collapseToMini() }
    }

    override fun onBackPressed() {
        collapseToMini()
    }

    /** Повторный запуск (шторка, мини-бар) на уже открытый экран: не плодим копии. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newId = intent.getStringExtra("videoId") ?: ""
        val newTitle = intent.getStringExtra("title") ?: ""
        if (newId.isNotBlank() && newId != videoId) {
            videoId = newId
            PlayerSession.open(this, newId, newTitle)
        }
        PlayerSession.host = this
        if (!isInPictureInPictureMode) {
            PlayerSession.attachTo(holder)
            PlayerSession.applyPipLayout(false)
        }
    }

    /** Свернуть в мини-плеер: ролик переезжает вниз оболочки и продолжает играть. */
    private fun collapseToMini() {
        // Защита от двойных срабатываний (жест и кнопка в один миг): одна свёртка на полторы секунды.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCollapseAt < 1500) {
            return
        }
        // Сразу после разворота не сворачиваем: мгновенные «жесты» в этот момент — помеха.
        if (now - PlayerSession.lastExpandAtMs < 1250) {
            return
        }
        lastCollapseAt = now
        DiagLog.add("движок-плеер: сворачиваю в мини-плеер")
        PlayerSession.captureWantPlay()
        PlayerSession.scheduleResumeAttempts()
        PlayerSession.detach()
        if (isTaskRoot) {
            startActivity(Intent(this, FeedActivity::class.java))
        }
        finish()
    }

    /** Системное окно «картинка-в-картинке» — отдельная кнопка. В окошке — только ролик и play/pause. */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val ok = runCatching { enterPictureInPictureMode(pipParams(playing = true)) }.getOrDefault(false)
        if (ok) {
            DiagLog.add("движок-плеер: свернул в окно")
            PlayerSession.markPip(this)
        } else {
            DiagLog.add("движок-плеер: окно недоступно")
        }
    }

    /** Параметры окна: пропорции ролика и кнопка play/pause. */
    private fun pipParams(playing: Boolean): PictureInPictureParams {
        val toggle = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, PipControls::class.java).setAction(PipControls.ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = if (playing) "Пауза" else "Играть"
        val action = RemoteAction(
            Icon.createWithResource(
                this,
                if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
            ),
            label,
            label,
            toggle,
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(action))
            .build()
    }

    /** Обновить кнопку в окне (вызывает сеанс ролика). */
    override fun applyPipParams(playing: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
            runCatching { setPictureInPictureParams(pipParams(playing)) }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        DiagLog.add(
            if (isInPictureInPictureMode) "движок-плеер: в окне" else "движок-плеер: развёрнут",
        )
        val visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        topBar.visibility = visibility
        statusView.visibility = visibility
        if (isInPictureInPictureMode) {
            PlayerSession.markPip(this)
            PlayerSession.keepAlive()
            PlayerSession.applyPipLayout(true)
            PlayerSession.captureWantPlay()
            PlayerSession.scheduleResumeAttempts()
            holder.postDelayed({ PlayerSession.updatePipParams() }, 700)
        } else {
            PlayerSession.clearPip(this)
            PlayerSession.applyPipLayout(false)
            // Заранее будим вывод кадров: после окна картинка любит чернеть.
            PlayerSession.nudgeSurface()
        }
    }

    /** Скорость и служебное: список скоростей, прямой поток, перезагрузка. */
    private fun showSettings() {
        val rates = listOf(1.0, 1.25, 1.5, 2.0, 2.5, 3.0)
        val labels = rates.map { "Скорость: " + speedLabel(it) }
        val entries = labels + listOf("Прямой поток", "Оживить видео (если чёрный экран)", "Обновить страницу (с этого места)", "Перезагрузить страницу")
        AlertDialog.Builder(this)
            .setTitle("Плеер")
            .setItems(entries.toTypedArray()) { _, which ->
                when {
                    which < rates.size -> {
                        PlayerSession.applySpeed(this, rates[which])
                        speedChip.text = speedLabel(rates[which])
                        DiagLog.add("движок-плеер: скорость ${speedLabel(rates[which])}")
                    }
                    which == rates.size -> {
                        if (videoId.isEmpty()) {
                            statusView.text = "движок-плеер: нет ролика для прямого потока"
                        } else {
                            DiagLog.add("движок-плеер: переключаю на прямой поток")
                            startActivity(
                                Intent(this, PlayerActivity::class.java)
                                    .putExtra("videoId", videoId),
                            )
                        }
                    }
                    which == rates.size + 1 -> {
                        PlayerSession.reviveVideo(this)
                        statusView.text = "движок-плеер: оживляю видео"
                    }
                    which == rates.size + 2 -> {
                        PlayerSession.reloadFromPosition()
                        statusView.text = "движок-плеер: обновляю страницу с места"
                    }
                    else -> {
                        PlayerSession.reload()
                        statusView.text = "движок-плеер: перезагрузка"
                    }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun speedLabel(rate: Double): String = when (rate) {
        1.0 -> "1×"
        1.25 -> "1,25×"
        1.5 -> "1,5×"
        2.0 -> "2×"
        2.5 -> "2,5×"
        3.0 -> "3×"
        else -> "$rate×"
    }

    companion object {

        /** Интент для открытия ролика (оболочка передаёт заголовок для мини-плеера). */
        fun createIntent(context: Context, videoId: String, title: String): Intent =
            Intent(context, EnginePlayerActivity::class.java)
                .putExtra("videoId", videoId)
                .putExtra("title", title)
    }
}
