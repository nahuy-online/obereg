package app.obereg.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import androidx.core.content.ContextCompat

/**
 * Служба медиа-уведомления: то самое «управление плеером в шторке».
 *
 * Держит сессию мультимедиа (для шторки, экрана блокировки и кнопок гарнитуры)
 * и постоянное уведомление с кнопками «предыдущий / пауза-играть / следующий /
 * повтор / закрыть», названием ролика, каналом и полосой времени.
 *
 * Собственного воспроизведения тут нет: ролик живёт в PlayerSession, а служба
 * лишь показывает его состояние и передаёт нажатия обратно в сеанс ролика.
 * Заодно служба защищает процесс от усыпления — фон играет стабильнее.
 */
class PlaybackService : Service() {

    private lateinit var session: MediaSession

    private var playing = false
    private var positionMs = 0L
    private var durationMs = 0L
    private var title = ""
    private var channel = ""
    private var videoId = ""
    private var loop = false

    private var artwork: Bitmap? = null
    private var artVideoId = ""

    private val artCache = LruCache<String, Bitmap>(8)

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()

        session = MediaSession(this, "Obereg")
        @Suppress("DEPRECATION")
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        if (Build.VERSION.SDK_INT >= 34) {
            session.setPlaybackToLocal(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
        }
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = PlayerSession.mediaPlay()
                override fun onPause() = PlayerSession.mediaPause()
                override fun onSkipToNext() = PlayerSession.mediaNext()
                override fun onSkipToPrevious() = PlayerSession.mediaPrevious()
                override fun onStop() = PlayerSession.mediaClose()
                override fun onSeekTo(pos: Long) = PlayerSession.mediaSeek(pos)
            },
            Handler(Looper.getMainLooper()),
        )
        session.isActive = true

        pushPlaybackState()
        pushMetadata()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> PlayerSession.togglePlayPause()
            ACTION_PLAY -> PlayerSession.mediaPlay()
            ACTION_PAUSE -> PlayerSession.mediaPause()
            ACTION_NEXT -> PlayerSession.mediaNext()
            ACTION_PREVIOUS -> PlayerSession.mediaPrevious()
            ACTION_REPEAT -> PlayerSession.mediaRepeat()
            ACTION_CLOSE -> PlayerSession.mediaClose()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching {
            session.isActive = false
            session.release()
        }
        if (instance === this) {
            instance = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Свежее состояние из PlayerSession: раз в секунду и по событиям. */
    private fun applyState(
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
        title: String,
        channel: String,
        videoId: String,
        loop: Boolean,
    ) {
        val stateChanged = this.playing != playing
        val metaChanged = this.title != title || this.channel != channel || this.loop != loop
        val idChanged = this.videoId != videoId

        this.playing = playing
        this.positionMs = positionMs
        this.durationMs = durationMs
        this.title = title
        this.channel = channel
        this.loop = loop
        if (idChanged) {
            this.videoId = videoId
            loadArtwork(videoId)
        }

        pushPlaybackState()
        if (stateChanged || metaChanged || idChanged) {
            pushMetadata()
            postNotification()
        }
    }

    /** Полоса времени и кнопки шторки тянут состояние из сессии. */
    private fun pushPlaybackState() {
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP
        val builder = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                state,
                PlaybackCodec.clampSeekMs(positionMs, durationMs),
                if (playing) 1f else 0f,
                SystemClock.elapsedRealtime(),
            )
        runCatching { session.setPlaybackState(builder.build()) }
    }

    private fun pushMetadata() {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title.ifBlank { "Оберег" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, channel)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Оберег")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        artwork?.let { builder.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
        runCatching { session.setMetadata(builder.build()) }
    }

    /** Обложка ролика: тянем с сервиса картинок и кэшируем по идентификатору. */
    private fun loadArtwork(videoId: String) {
        artwork = null
        if (videoId.length != 11) {
            artVideoId = ""
            return
        }
        if (videoId == artVideoId) {
            return
        }
        artVideoId = videoId
        val cached = artCache.get(videoId)
        if (cached != null) {
            artwork = cached
            pushMetadata()
            postNotification()
            return
        }
        Thread {
            val bitmap = runCatching {
                java.net.URL("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
                    .openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            Handler(Looper.getMainLooper()).post {
                if (artVideoId != videoId || bitmap == null) {
                    return@post
                }
                artwork = bitmap
                artCache.put(videoId, bitmap)
                pushMetadata()
                postNotification()
            }
        }.start()
    }

    private fun postNotification() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            EnginePlayerActivity.createIntent(this, PlayerSession.videoId, PlayerSession.title),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val togglePi = servicePi(REQ_TOGGLE, ACTION_TOGGLE)
        val closePi = servicePi(REQ_CLOSE, ACTION_CLOSE)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_obereg)
            .setContentTitle(title.ifBlank { "Оберег" })
            .setContentText(channel.ifBlank { "воспроизведение" })
            .setContentIntent(openPi)
            .setDeleteIntent(closePi)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(0xFF7AA2FF.toInt())
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_media_previous),
                    "Предыдущий",
                    servicePi(REQ_PREV, ACTION_PREVIOUS),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
                    ),
                    if (playing) "Пауза" else "Играть",
                    togglePi,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_media_next),
                    "Следующий",
                    servicePi(REQ_NEXT, ACTION_NEXT),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        if (loop) R.drawable.ic_media_repeat_on else R.drawable.ic_media_repeat,
                    ),
                    "Повтор",
                    servicePi(REQ_REPEAT, ACTION_REPEAT),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_media_close),
                    "Закрыть",
                    closePi,
                ).build(),
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
        artwork?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private fun servicePi(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Воспроизведение",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Управление роликом: плей/пауза, переключение, закрытие"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    companion object {

        private const val CHANNEL_ID = "obereg_playback"
        private const val NOTIFICATION_ID = 4211
        private const val REQ_OPEN = 11
        private const val REQ_TOGGLE = 12
        private const val REQ_PREV = 13
        private const val REQ_NEXT = 14
        private const val REQ_REPEAT = 15
        private const val REQ_CLOSE = 16

        const val ACTION_TOGGLE = "app.obereg.player.playback.TOGGLE"
        const val ACTION_PLAY = "app.obereg.player.playback.PLAY"
        const val ACTION_PAUSE = "app.obereg.player.playback.PAUSE"
        const val ACTION_NEXT = "app.obereg.player.playback.NEXT"
        const val ACTION_PREVIOUS = "app.obereg.player.playback.PREVIOUS"
        const val ACTION_REPEAT = "app.obereg.player.playback.REPEAT"
        const val ACTION_CLOSE = "app.obereg.player.playback.CLOSE"

        @Volatile
        private var instance: PlaybackService? = null

        /** Поднять службу медиа-уведомления (когда открывается ролик). */
        fun ensure(context: Context) {
            if (instance != null) {
                return
            }
            val app = context.applicationContext
            runCatching {
                ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
            }
        }

        /** Погасить службу: ролик закрыт, уведомление больше не нужно. */
        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, PlaybackService::class.java)) }
        }

        /** Свежее состояние из PlayerSession. Если службы нет — тихо выходим. */
        fun update(
            playing: Boolean,
            positionMs: Long,
            durationMs: Long,
            title: String,
            channel: String,
            videoId: String,
            loop: Boolean,
        ) {
            instance?.applyState(playing, positionMs, durationMs, title, channel, videoId, loop)
        }
    }
}
