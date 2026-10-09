package app.obereg.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Кнопки в системном окне «картинка-в-картинке»: пауза и воспроизведение. */
class PipControls : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            PlayerSession.togglePlayPauseFromPip()
        }
    }

    companion object {
        const val ACTION_TOGGLE = "app.obereg.player.PIP_TOGGLE"
    }
}
