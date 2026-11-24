package ar.mateo.controlflow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the Chromecast connection alive while something is playing and shows media controls
 * in the notification shade and on the lock screen. Stops by itself when nothing plays for a while.
 */
class CastService : Service() {
    companion object {
        private const val CHANNEL = "cast_controls"
        private const val NOTIF_ID = 42
        const val ACTION_PLAY_PAUSE = "ar.mateo.controlflow.PLAY_PAUSE"
        const val ACTION_NEXT = "ar.mateo.controlflow.NEXT"
        const val ACTION_PREV = "ar.mateo.controlflow.PREV"
        const val ACTION_CLOSE = "ar.mateo.controlflow.CLOSE"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSessionCompat
    private var artUrl: String? = null
    private var art: Bitmap? = null
    private var idleSince = 0L
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CastCtl.init(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Control del Chromecast", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            })
        }
        session = MediaSessionCompat(this, "ControlFlowCast").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { CastCtl.play() }
                override fun onPause() { CastCtl.pause() }
                override fun onSkipToNext() { CastCtl.next() }
                override fun onSkipToPrevious() { CastCtl.previous() }
                override fun onFastForward() { CastCtl.seekBy(10.0) }
                override fun onRewind() { CastCtl.seekBy(-10.0) }
                override fun onSeekTo(pos: Long) { CastCtl.seekTo(pos / 1000.0) }
                override fun onStop() { finish() }
            })
            isActive = true
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, build(CastCtl.state.value), type)

        scope.launch { CastCtl.state.collect { update(it) } }
        // Stop when nothing has been playing for 20 seconds
        scope.launch {
            while (true) {
                delay(5000)
                val st = CastCtl.state.value
                if (st.media == null || st.conn is Conn.Error || st.conn == Conn.Idle) {
                    if (idleSince == 0L) idleSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - idleSince > 20_000) finish()
                } else idleSince = 0L
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> CastCtl.playPause()
            ACTION_NEXT -> CastCtl.next()
            ACTION_PREV -> CastCtl.previous()
            ACTION_CLOSE -> { CastCtl.dismissedTitle = CastCtl.state.value.media?.title; finish() }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        if (stopped) return
        stopped = true
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        try { session.isActive = false; session.release() } catch (_: Exception) {}
        CastCtl.serviceStopped()
        super.onDestroy()
    }

    private fun update(st: CastState) {
        if (stopped) return
        val m = st.media
        if (m?.image != artUrl) {
            artUrl = m?.image
            art = null
            val url = artUrl
            if (url != null) scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    try {
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.connectTimeout = 5000; c.readTimeout = 8000
                        c.inputStream.use { BitmapFactory.decodeStream(it) }
                    } catch (_: Exception) { null }
                }
                if (url == artUrl) { art = bmp; update(CastCtl.state.value) }
            }
        }
        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, m?.title ?: st.appName ?: "Chromecast")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, m?.subtitle ?: st.appName ?: "")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, ((m?.duration ?: 0.0) * 1000).toLong())
        art?.let { meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(meta.build())

        val playing = m?.state == "PLAYING"
        val pbState = when (m?.state) {
            "PLAYING" -> PlaybackStateCompat.STATE_PLAYING
            "BUFFERING" -> PlaybackStateCompat.STATE_BUFFERING
            "PAUSED" -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_STOP
                )
                .setState(pbState, ((m?.positionNow() ?: 0.0) * 1000).toLong(), if (playing) (m?.rate ?: 1.0).toFloat() else 0f)
                .build()
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIF_ID, build(st)) } catch (_: Exception) {}
    }

    private fun pi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, CastService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun build(st: CastState): android.app.Notification {
        val m = st.media
        val playing = m?.state == "PLAYING" || m?.state == "BUFFERING"
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(m?.title ?: st.appName ?: "Chromecast")
            .setContentText(listOfNotNull(st.appName, CastCtl.name).joinToString(" · "))
            .setLargeIcon(art)
            .setContentIntent(open)
            .setDeleteIntent(pi(ACTION_CLOSE, 4))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", pi(ACTION_PREV, 1))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pausa" else "Play", pi(ACTION_PLAY_PAUSE, 2)
            )
            .addAction(android.R.drawable.ic_media_next, "Siguiente", pi(ACTION_NEXT, 3))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cerrar", pi(ACTION_CLOSE, 5))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}
