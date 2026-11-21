package io.github.est4s.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import io.github.est4s.terminal.core.mediaFolderQueue
import io.github.est4s.terminal.core.mediaStep
import io.github.est4s.terminal.core.nextSpeed
import io.github.est4s.terminal.core.previousRestarts
import io.github.est4s.terminal.core.seekBy
import java.io.File
import java.io.FileInputStream

private const val CHANNEL_ID = "media"
// Past every ID TerminalService uses (up to the chats' 6000s).
private const val NOTIFICATION_ID = 9000
private const val ACTION_TOGGLE = "io.github.est4s.terminal.media.TOGGLE"
private const val ACTION_NEXT = "io.github.est4s.terminal.media.NEXT"
private const val ACTION_PREVIOUS = "io.github.est4s.terminal.media.PREVIOUS"
private const val ACTION_CLOSE = "io.github.est4s.terminal.media.CLOSE"
// While ducked for another app's short sound.
private const val DUCK_VOLUME = 0.2f

/** What the player screen shows: from [MediaPlaybackService] (audio) or the screen's own player (video). */
data class MediaState(
    val path: String = "",
    val playing: Boolean = false,
    val position: Long = 0,
    /** 0 while unknown. */
    val duration: Long = 0,
    val speed: Float = 1f,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val loading: Boolean = true,
    /** Why it can't play, shown on screen with Open with another app. */
    val error: String? = null,
    /** Closed from the notification: the screen goes too. */
    val closed: Boolean = false,
)

/**
 * Plays audio files (the player screen's, [MediaViewActivity]) so they
 * keep playing with the screen off or the app left: a foreground service
 * (mediaPlayback) owning the MediaPlayer, with a MediaSession for the
 * lock screen and headset buttons and a media notification (previous,
 * play/pause, next, close). The screen binds to it and reads [state];
 * it starts the service too, so playing outlives the binding. Pauses
 * when another app takes the audio focus or the headphones come out.
 * [close] (Back on the screen, the notification's Close) stops it.
 */
class MediaPlaybackService : Service() {
    inner class LocalBinder : Binder() {
        val service get() = this@MediaPlaybackService
    }

    private val binder = LocalBinder()
    private var player: MediaPlayer? = null
    private var prepared = false
    private var hostFile: File? = null
    private var hostFolder: File? = null
    private var queue: List<String> = emptyList()
    private var path = ""
    private var speed = 1f
    private var error: String? = null
    private var closed = false
    private var foreground = false
    // Paused by a short loss of the audio focus (a call, a navigation prompt): plays again when it's back.
    private var resumeOnFocus = false
    private var noisyRegistered = false
    private lateinit var session: MediaSession
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change -> onFocusChange(change) }
            .build()
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "mynx").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToNext() = step(1)
                override fun onSkipToPrevious() = step(-1)
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = close()
            })
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> step(1)
            ACTION_PREVIOUS -> step(-1)
            ACTION_CLOSE -> close()
        }
        // Started again after Android killed it, it has nothing to play.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releasePlayer()
        abandonFocus()
        session.release()
        super.onDestroy()
    }

    /** The player's state now, its position too. */
    val state: MediaState
        get() {
            val p = player
            return MediaState(
                path = path,
                playing = p != null && prepared && runCatching { p.isPlaying }.getOrDefault(false),
                position = if (p != null && prepared) runCatching { p.currentPosition.toLong() }.getOrDefault(0L) else 0L,
                duration = if (p != null && prepared) runCatching { p.duration.toLong() }.getOrDefault(0L).coerceAtLeast(0L) else 0L,
                speed = speed,
                hasPrevious = mediaStep(queue, path, -1) != null,
                hasNext = mediaStep(queue, path, 1) != null,
                loading = p != null && !prepared && error == null,
                error = error,
                closed = closed,
            )
        }

    /**
     * Plays [debianPath] (its file on the phone [host]) and makes its
     * folder's audio files the queue; nothing changes if it's already
     * the one loaded (the screen opened again from the notification).
     */
    fun open(host: File, debianPath: String) {
        closed = false
        if (debianPath == path && player != null && error == null) return
        hostFile = host
        hostFolder = host.parentFile
        val names = host.parentFile?.list()?.toList() ?: emptyList()
        queue = mediaFolderQueue(names, host.name, debianPath)
        load(debianPath, play = true)
    }

    fun toggle() = if (state.playing) pause() else play()

    fun play() {
        val p = player ?: return
        if (!prepared) return
        if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        resumeOnFocus = false
        p.setVolume(1f, 1f)
        // Setting a speed starts playing too; 1× needs no params (some files refuse them).
        runCatching {
            if (speed != 1f) p.playbackParams = p.playbackParams.setSpeed(speed) else p.start()
        }.onFailure { p.start() }
        listenForNoisy(true)
        changed()
    }

    fun pause() {
        val p = player ?: return
        if (prepared && runCatching { p.isPlaying }.getOrDefault(false)) p.pause()
        listenForNoisy(false)
        changed()
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        if (!prepared) return
        p.seekTo(ms.coerceAtLeast(0), MediaPlayer.SEEK_CLOSEST)
        changed()
    }

    /** ⟲10 and 10⟳: [delta] ms, within the track. */
    fun skip(delta: Long) {
        val now = state
        seekTo(seekBy(now.position, delta, now.duration))
    }

    /** Previous (-1) or next (1) in the folder; previous starts the track again once it's 3 s in. */
    fun step(direction: Int) {
        if (direction < 0 && previousRestarts(state.position)) {
            seekTo(0)
            return
        }
        val to = mediaStep(queue, path, direction) ?: run {
            if (direction < 0) seekTo(0)
            return
        }
        load(to, play = true)
    }

    fun cycleSpeed() {
        speed = nextSpeed(speed)
        val p = player
        // While paused it waits for play: setting it would start playing.
        if (p != null && prepared && runCatching { p.isPlaying }.getOrDefault(false)) {
            runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
        }
        changed()
    }

    /** Stops playing, takes the notification away and ends the service. */
    fun close() {
        closed = true
        releasePlayer()
        abandonFocus()
        listenForNoisy(false)
        session.isActive = false
        path = ""
        queue = emptyList()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    // The host file for a queue entry: the one opened, or its namesake beside it.
    private fun hostOf(debianPath: String): File? {
        val opened = hostFile ?: return null
        if (debianPath.substringAfterLast('/') == opened.name) return opened
        return File(hostFolder ?: return null, debianPath.substringAfterLast('/'))
    }

    private fun load(debianPath: String, play: Boolean) {
        releasePlayer()
        path = debianPath
        error = null
        val file = hostOf(debianPath)
        val name = debianPath.substringAfterLast('/')
        val next = MediaPlayer()
        player = next
        prepared = false
        next.setAudioAttributes(attributes)
        next.setWakeMode(this, android.os.PowerManager.PARTIAL_WAKE_LOCK)
        next.setOnPreparedListener {
            if (player !== it) return@setOnPreparedListener
            prepared = true
            if (play) play() else changed()
        }
        next.setOnCompletionListener {
            if (player !== it) return@setOnCompletionListener
            val to = mediaStep(queue, path, 1)
            if (to != null) load(to, play = true) else {
                it.seekTo(0)
                listenForNoisy(false)
                changed()
            }
        }
        next.setOnErrorListener { mp, what, extra ->
            if (player === mp) fail("Android can't play $name (error $what, $extra).")
            true
        }
        try {
            if (file == null || !file.isFile) {
                fail("$name isn't there any more.")
                return
            }
            // The media server can't open files in the app's storage itself.
            FileInputStream(file).use { next.setDataSource(it.fd) }
            next.prepareAsync()
        } catch (e: Exception) {
            fail("Android can't play $name: ${e.message ?: e.javaClass.simpleName}")
        }
        changed()
    }

    private fun fail(why: String) {
        error = why
        prepared = false
        player?.let { runCatching { it.release() } }
        player = null
        listenForNoisy(false)
        changed()
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.release() } }
        player = null
        prepared = false
    }

    private fun onFocusChange(change: Int) {
        val p = player ?: return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocus = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val wasPlaying = state.playing
                pause()
                resumeOnFocus = wasPlaying
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> runCatching { p.setVolume(DUCK_VOLUME, DUCK_VOLUME) }
            AudioManager.AUDIOFOCUS_GAIN -> {
                runCatching { p.setVolume(1f, 1f) }
                if (resumeOnFocus) play()
            }
        }
    }

    private fun abandonFocus() {
        runCatching { audio.abandonAudioFocusRequest(focusRequest) }
    }

    private fun listenForNoisy(on: Boolean) {
        if (on == noisyRegistered) return
        noisyRegistered = on
        if (on) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(noisy, filter)
            }
        } else {
            runCatching { unregisterReceiver(noisy) }
        }
    }

    // The session and the notification follow every change.
    private fun changed() {
        if (closed) return
        val now = state
        val name = path.substringAfterLast('/')
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, name)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, name)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, now.duration)
            .build())
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            (if (now.hasNext) PlaybackState.ACTION_SKIP_TO_NEXT else 0L)
        val stateCode = when {
            now.error != null -> PlaybackState.STATE_ERROR
            now.loading -> PlaybackState.STATE_BUFFERING
            now.playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(actions)
            .setState(stateCode, now.position, if (now.playing) now.speed else 0f)
            .apply { now.error?.let { setErrorMessage(it) } }
            .build())
        session.isActive = true
        val notification = buildNotification(now, name)
        if (!foreground) {
            // The screen starts this while it's on screen, where Android lets a service go foreground.
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                foreground = true
            }
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(now: MediaState, name: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Playing audio", NotificationManager.IMPORTANCE_LOW))
        fun action(icon: Int, title: String, what: String, code: Int) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), title,
            PendingIntent.getService(this, code, Intent(this, MediaPlaybackService::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        // Opens the screen again; CLEAR_TOP and SINGLE_TOP reuse one that's still there.
        val open = hostOf(path)?.let { file ->
            PendingIntent.getActivity(this, 0,
                MediaViewActivity.intent(this, file, path)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(now.error ?: path.substringBeforeLast('/'))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(now.playing)
            .setShowWhen(false)
            .apply { if (open != null) setContentIntent(open) }
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS, 1))
            .addAction(
                if (now.playing) action(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE, 2)
                else action(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE, 2),
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT, 3))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Close", ACTION_CLOSE, 4))
            .setStyle(Notification.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    companion object {
        /** Starts the service (from the screen, while it's on screen) so it outlives the binding. */
        fun start(context: Context) {
            context.startService(Intent(context, MediaPlaybackService::class.java))
        }
    }
}
