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
 * а отдельная кнопка «В окне» отправляет его в системное окно поверх других приложений.
 * Скорость — чип «1×» в шапке. Пока идёт «картинка-в-картинке», вся наша шапка скрыта,
 * чтобы в окошке был только ролик.
 */
class EnginePlayerActivity : AppCompatActivity(), PlayerSession.Host {

    private lateinit var holder: FrameLayout
    private lateinit var statusView: TextView
    private lateinit var topBar: View
    private lateinit var speedChip: TextView
    private var videoId = ""

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
    }

    override fun onResume() {
        super.onResume()
        PlayerSession.host = this
        if (!PlayerSession.pipActive) {
            PlayerSession.attachTo(holder)
        }
        speedChip.text = speedLabel(PlayerSession.speed)
    }

    override fun onDestroy() {
        if (PlayerSession.host === this) {
            PlayerSession.host = null
        }
        PlayerSession.lastStatus = null
        super.onDestroy()
    }

    override fun onMinimizeRequested() {
        runOnUiThread { collapseToMini() }
    }

    override fun onBackPressed() {
        collapseToMini()
    }

    /** Свернуть в мини-плеер: ролик переезжает вниз оболочки и продолжает играть. */
    private fun collapseToMini() {
        DiagLog.add("движок-плеер: сворачиваю в мини-плеер")
        PlayerSession.requestResumeAfterRebind()
        PlayerSession.detach()
        if (isTaskRoot) {
            startActivity(Intent(this, FeedActivity::class.java))
        }
        finish()
    }

    /** Системное окно «картинка-в-картинке» — отдельная кнопка. */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val openShell = PendingIntent.getActivity(
            this,
            0,
            Intent(this, FeedActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_nav_home),
            "Оберег",
            "Открыть ленту",
            openShell,
        )
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(openAction))
            .build()
        val ok = runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
        if (ok) {
            DiagLog.add("движок-плеер: свернул в окно")
            PlayerSession.markPip(this)
        } else {
            DiagLog.add("движок-плеер: окно недоступно")
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
            holder.postDelayed({ PlayerSession.resumeIfPaused() }, 350)
        } else {
            PlayerSession.clearPip(this)
        }
    }

    /** Скорость и служебное: список скоростей, прямой поток, перезагрузка. */
    private fun showSettings() {
        val rates = listOf(1.0, 1.25, 1.5, 2.0, 2.5, 3.0)
        val labels = rates.map { "Скорость: " + speedLabel(it) }
        val entries = labels + listOf("Прямой поток", "Перезагрузить страницу")
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
