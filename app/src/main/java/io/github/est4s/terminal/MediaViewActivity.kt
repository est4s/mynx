package io.github.est4s.terminal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.est4s.terminal.core.CONTROLS_HIDE_MS
import io.github.est4s.terminal.core.FileKind
import io.github.est4s.terminal.core.SEEK_STEP_MS
import io.github.est4s.terminal.core.doubleTapSeek
import io.github.est4s.terminal.core.fileKindOf
import io.github.est4s.terminal.core.mediaFolder
import io.github.est4s.terminal.core.mediaTime
import io.github.est4s.terminal.core.nextSpeed
import io.github.est4s.terminal.core.seekBy
import io.github.est4s.terminal.core.speedLabel
import io.github.est4s.terminal.core.videoFit
import java.io.File
import java.io.FileInputStream

private const val EXTRA_MEDIA_FILE = "file"
private const val EXTRA_MEDIA_PATH = "path"
// How often the seek bar and times follow the player.
private const val TICK_MS = 250L

/**
 * mynx's player for audio and video files (docs/TOUCH-DESIGN.md "The
 * media player"), started with the file on the phone and its Debian path
 * ([intent]). Video plays here, full screen on black, and pauses when the
 * screen is left; a tap shows the controls, a double tap seeks 10 s.
 * Audio plays in [MediaPlaybackService], so it goes on with the screen
 * off or the app left; this screen binds to it and shows its state.
 * Leaving it with Back (finishing) stops the audio; the notification
 * opens it again. A file Android can't play says so, with Open with
 * another app (TerminalService.openDebianFile).
 */
class MediaViewActivity : Activity(), FrontActivity {
    private lateinit var hostFile: File
    private lateinit var debianPath: String
    private var video = false
    private val main = Handler(Looper.getMainLooper())
    private val asks = ActivityAsks(this)
    private var visible = false

    private var audioView: AudioPlayerView? = null
    private var media: MediaPlaybackService? = null
    private var mediaBound = false
    private val mediaConnection = object : ServiceConnection {
        override fun onServiceConnected(component: ComponentName, binder: IBinder) {
            val s = (binder as MediaPlaybackService.LocalBinder).service
            media = s
            s.open(hostFile, debianPath)
            refresh()
        }

        override fun onServiceDisconnected(component: ComponentName) {
            media = null
        }
    }

    // Open with another app goes through the terminal's service, if it runs (it does while mynx does).
    private var terminal: TerminalService? = null
    private var terminalBound = false
    private val terminalConnection = object : ServiceConnection {
        override fun onServiceConnected(component: ComponentName, binder: IBinder) {
            terminal = (binder as TerminalService.LocalBinder).service
        }

        override fun onServiceDisconnected(component: ComponentName) {
            terminal = null
        }
    }

    private var videoControls: VideoControlsView? = null
    private var videoFrame: VideoFrame? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var videoError: String? = null
    private var videoSpeed = 1f
    // Where to start when the surface comes back (after leaving the screen), and whether to play.
    private var resumeAt = 0L
    private var playWhenReady = true
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val movieAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(movieAttributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) videoPause()
            }
            .build()
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (visible) main.postDelayed(this, TICK_MS)
        }
    }
    private val hideControls = Runnable {
        if (videoPlaying()) videoControls?.visibility = View.GONE
    }

    private val controls = object : MediaControls {
        override fun back() = finish()
        override fun toggle() = if (video) videoToggle() else media?.toggle() ?: Unit
        override fun seekTo(ms: Long) = if (video) videoSeekTo(ms) else media?.seekTo(ms) ?: Unit
        override fun skip(delta: Long) = if (video) videoSeekTo(seekBy(videoPosition(), delta, videoDuration())) else media?.skip(delta) ?: Unit
        override fun step(direction: Int) = media?.step(direction) ?: Unit
        override fun cycleSpeed() = if (video) videoCycleSpeed() else media?.cycleSpeed() ?: Unit
        override fun openWith() = openWithAnotherApp()
        override fun touched() = showControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hostFile = File(intent.getStringExtra(EXTRA_MEDIA_FILE) ?: "")
        debianPath = intent.getStringExtra(EXTRA_MEDIA_PATH) ?: hostFile.path
        val name = debianPath.substringAfterLast('/')
        title = name
        video = fileKindOf(name) == FileKind.VIDEO
        if (video) buildVideo(name) else buildAudio(name)
        bindService(Intent(this, TerminalService::class.java), terminalConnection, 0).also { terminalBound = it }
        if (!video) {
            MediaPlaybackService.start(this)
            mediaBound = bindService(Intent(this, MediaPlaybackService::class.java), mediaConnection, BIND_AUTO_CREATE)
        }
    }

    // Opened again from the media notification with another file.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val path = intent.getStringExtra(EXTRA_MEDIA_PATH)
        setIntent(intent)
        if (path != null && path != debianPath) recreate()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        main.removeCallbacks(tick)
        tick.run()
    }

    override fun onResume() {
        super.onResume()
        FrontActivities.resumed(this)
        if (video) hideBars()
    }

    override fun onPause() {
        // Video pauses when the screen is left; audio goes on in its service.
        if (video) videoPause()
        super.onPause()
    }

    override fun onStop() {
        visible = false
        main.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        asks.clear()
        FrontActivities.gone(this)
        releaseVideo()
        if (video) runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        // Back (or ←) closes the player; the service is left playing another file's audio alone.
        if (isFinishing) media?.takeIf { it.state.path == debianPath }?.close()
        if (mediaBound) runCatching { unbindService(mediaConnection) }
        mediaBound = false
        if (terminalBound) runCatching { unbindService(terminalConnection) }
        terminalBound = false
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && video) hideBars()
    }

    // The keyboard-first rule: space plays and pauses, ← → seek, [ ] previous and next, s the speed.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> controls.toggle()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (!currentState().playing) controls.toggle()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (currentState().playing) controls.toggle()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> controls.skip(-SEEK_STEP_MS)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> controls.skip(SEEK_STEP_MS)
            KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controls.step(-1)
            KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_MEDIA_NEXT -> controls.step(1)
            KeyEvent.KEYCODE_S -> controls.cycleSpeed()
            KeyEvent.KEYCODE_ESCAPE -> finish()
            else -> return super.onKeyDown(keyCode, event)
        }
        if (video) showControls()
        refresh()
        return true
    }

    // FrontActivity: so the terminal's service can open the file in another app from here.
    override val activity: Activity get() = this
    override val onScreen: Boolean get() = visible
    override val displayRotation: Int get() = asks.displayRotation

    override fun askPermissions(permissions: Array<String>, answer: (Boolean) -> Unit) = asks.askPermissions(permissions, answer)

    override fun startForResult(intent: Intent, answer: (Boolean) -> Unit): Boolean = asks.startForResult(intent, answer)

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        asks.onActivityResult(requestCode, resultCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        asks.onRequestPermissionsResult(requestCode, grantResults)
    }

    private fun openWithAnotherApp() {
        val s = terminal
        val why = if (s == null) "mynx isn't running: open the file from Files." else s.openDebianFile(debianPath)
        if (why != null) {
            audioView?.note(why)
            videoControls?.note(why)
        }
    }

    private fun currentState(): MediaState = if (video) videoState() else media?.state ?: MediaState(path = debianPath)

    private fun refresh() {
        val state = currentState()
        if (state.closed) {
            finish()
            return
        }
        audioView?.update(state)
        videoControls?.update(state)
    }

    private fun buildAudio(name: String) {
        window.decorView.setBackgroundColor(Pixel.BACKGROUND)
        val view = AudioPlayerView(this, name, mediaFolder(debianPath), controls)
        audioView = view
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val sides = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(sides.left, sides.top, sides.right, sides.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(view)
    }

    private fun buildVideo(name: String) {
        window.decorView.setBackgroundColor(Color.BLACK)
        val surface = SurfaceView(this)
        val frame = VideoFrame(this).apply { addView(surface) }
        videoFrame = frame
        val overlay = VideoControlsView(this, name, controls)
        videoControls = overlay
        val pad = TouchPad(this,
            tap = { toggleControls() },
            doubleTap = { x, width ->
                controls.skip(doubleTapSeek(x, width))
                refresh()
            },
        )
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(frame, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(pad, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // The picture goes under the bars and the camera hole; the controls keep clear of them.
            setOnApplyWindowInsetsListener { _, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val sides = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    overlay.setPadding(sides.left, sides.top, sides.right, sides.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    overlay.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        setContentView(root)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = startVideo(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Gone with the screen: back where it was, paused, when it returns.
                resumeAt = videoPosition()
                playWhenReady = false
                releaseVideo()
            }
        })
        showControls()
    }

    private fun startVideo(holder: SurfaceHolder) {
        if (videoError != null) return
        val name = debianPath.substringAfterLast('/')
        val p = MediaPlayer()
        player = p
        prepared = false
        p.setAudioAttributes(movieAttributes)
        p.setDisplay(holder)
        p.setScreenOnWhilePlaying(true)
        p.setOnVideoSizeChangedListener { _, w, h -> videoFrame?.setVideoSize(w, h) }
        p.setOnPreparedListener {
            if (player !== it) return@setOnPreparedListener
            prepared = true
            videoFrame?.setVideoSize(it.videoWidth, it.videoHeight)
            if (resumeAt > 0) it.seekTo(resumeAt, MediaPlayer.SEEK_CLOSEST)
            if (playWhenReady) videoPlay()
            refresh()
        }
        p.setOnCompletionListener {
            main.removeCallbacks(hideControls)
            videoControls?.visibility = View.VISIBLE
            refresh()
        }
        p.setOnErrorListener { mp, what, extra ->
            if (player === mp) videoFail("Android can't play $name (error $what, $extra).")
            true
        }
        try {
            if (!hostFile.isFile) {
                videoFail("$name isn't there any more.")
                return
            }
            // The media server can't open files in the app's storage itself.
            FileInputStream(hostFile).use { p.setDataSource(it.fd) }
            p.prepareAsync()
        } catch (e: Exception) {
            videoFail("Android can't play $name: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun videoFail(why: String) {
        videoError = why
        releaseVideo()
        main.removeCallbacks(hideControls)
        videoControls?.visibility = View.VISIBLE
        refresh()
    }

    private fun releaseVideo() {
        player?.let { runCatching { it.release() } }
        player = null
        prepared = false
    }

    private fun videoPlaying(): Boolean = player?.takeIf { prepared }?.let { runCatching { it.isPlaying }.getOrDefault(false) } ?: false

    private fun videoPosition(): Long = player?.takeIf { prepared }?.let { runCatching { it.currentPosition.toLong() }.getOrNull() } ?: resumeAt

    private fun videoDuration(): Long = player?.takeIf { prepared }?.let { runCatching { it.duration.toLong() }.getOrNull() }?.coerceAtLeast(0) ?: 0

    private fun videoState() = MediaState(
        path = debianPath,
        playing = videoPlaying(),
        position = videoPosition(),
        duration = videoDuration(),
        speed = videoSpeed,
        loading = player != null && !prepared && videoError == null,
        error = videoError,
    )

    private fun videoToggle() = if (videoPlaying()) videoPause() else videoPlay()

    private fun videoPlay() {
        val p = player ?: run {
            playWhenReady = true
            return
        }
        if (!prepared) return
        audioManager.requestAudioFocus(focusRequest)
        // Setting a speed starts playing too; 1× needs no params.
        runCatching {
            if (videoSpeed != 1f) p.playbackParams = p.playbackParams.setSpeed(videoSpeed) else p.start()
        }.onFailure { runCatching { p.start() } }
        showControls()
    }

    private fun videoPause() {
        val p = player ?: return
        if (videoPlaying()) p.pause()
        main.removeCallbacks(hideControls)
        refresh()
    }

    private fun videoSeekTo(ms: Long) {
        val p = player
        if (p == null || !prepared) {
            resumeAt = ms.coerceAtLeast(0)
            return
        }
        p.seekTo(ms.coerceAtLeast(0), MediaPlayer.SEEK_CLOSEST)
    }

    private fun videoCycleSpeed() {
        videoSpeed = nextSpeed(videoSpeed)
        val p = player
        if (p != null && videoPlaying()) runCatching { p.playbackParams = p.playbackParams.setSpeed(videoSpeed) }
    }

    // Shows the video's controls; while it plays they go again after CONTROLS_HIDE_MS.
    private fun showControls() {
        val overlay = videoControls ?: return
        overlay.visibility = View.VISIBLE
        main.removeCallbacks(hideControls)
        if (videoPlaying()) main.postDelayed(hideControls, CONTROLS_HIDE_MS)
    }

    private fun toggleControls() {
        val overlay = videoControls ?: return
        if (overlay.visibility != View.VISIBLE) {
            showControls()
            return
        }
        main.removeCallbacks(hideControls)
        overlay.visibility = View.GONE
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    companion object {
        /** Plays [hostFile] (the file on the phone), the Debian file [debianPath]: video here, audio through [MediaPlaybackService]. */
        fun intent(context: Context, hostFile: File, debianPath: String): Intent =
            Intent(context, MediaViewActivity::class.java)
                .putExtra(EXTRA_MEDIA_FILE, hostFile.path)
                .putExtra(EXTRA_MEDIA_PATH, debianPath)
    }
}

/** What the player's buttons do: the screen passes them to the service (audio) or its own player (video). */
interface MediaControls {
    fun back()
    fun toggle()
    fun seekTo(ms: Long)
    fun skip(delta: Long)
    fun step(direction: Int)
    fun cycleSpeed()
    fun openWith()
    /** Any touch on the video's controls: they stay up a while longer. */
    fun touched() {}
}

/**
 * The audio player's screen: ←, a note, the name and its folder (dim),
 * the seek bar and times, previous, a big play/pause, next and the
 * speed. A file that can't play shows why and Open with another app.
 * Drawn from [update]'s [MediaState], so screenshots need no player.
 */
@SuppressLint("ViewConstructor")
class AudioPlayerView(context: Context, name: String, folder: String, private val controls: MediaControls) : LinearLayout(context) {
    private val art: FrameLayout
    private val seek = MediaSeek(context, controls, light = false)
    private val transport: LinearLayout
    private val playButton: ImageView
    private val previous: ImageView
    private val next: ImageView
    private val speed: TextView
    private val message: TextView
    private val openWith: TextView
    private var shownPlaying: Boolean? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Pixel.BACKGROUND)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(mediaIconButton(context, PixelIcon.BACK, Pixel.CYAN, "Back", 20) { controls.back() },
                LayoutParams(dp(48), dp(48)))
        }, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(View(context), LayoutParams(MATCH_PARENT, 0, 1f))
        art = FrameLayout(context).apply {
            background = Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE, shadow = true)
            val s = dp(Pixel.SHADOW_DP)
            setPadding(0, 0, s, s)
            addView(ImageView(context).apply {
                setImageDrawable(Pixel.icon(context, PixelIcon.NOTE, Pixel.CYAN, 96))
                scaleType = ImageView.ScaleType.CENTER
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        addView(art, LayoutParams(dp(176), dp(176)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(28)
        })
        addView(TextView(context).apply {
            text = name
            setTextColor(Pixel.TEXT)
            typeface = Pixel.label(context, 700)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, sideParams())
        addView(TextView(context).apply {
            text = folder
            setTextColor(Pixel.DIM)
            typeface = Pixel.text(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }, sideParams().apply { topMargin = dp(4) })
        message = TextView(context).apply {
            setTextColor(Pixel.TEXT)
            typeface = Pixel.text(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            visibility = GONE
        }
        addView(message, sideParams().apply { topMargin = dp(24) })
        openWith = mediaTextButton(context, "Open with another app", main = true) { controls.openWith() }.apply { visibility = GONE }
        addView(openWith, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(16)
        })
        addView(seek, sideParams().apply { topMargin = dp(24) })
        previous = mediaIconButton(context, PixelIcon.PREVIOUS, Pixel.TEXT, "Previous", 28) { controls.step(-1) }
        next = mediaIconButton(context, PixelIcon.NEXT, Pixel.TEXT, "Next", 28) { controls.step(1) }
        playButton = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            background = Pixel.box(context, fill = Pixel.CYAN, border = 0, ripple = Pixel.RIPPLE_ON_CYAN)
            contentDescription = "Play"
            isFocusable = true
            setOnClickListener { controls.toggle() }
        }
        speed = mediaTextButton(context, speedLabel(1f), main = false) { controls.cycleSpeed() }.apply {
            contentDescription = "Speed"
            minWidth = dp(72)
        }
        transport = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            addView(previous, LayoutParams(dp(64), dp(64)))
            addView(playButton, LayoutParams(dp(80), dp(80)).apply {
                leftMargin = dp(24)
                rightMargin = dp(24)
            })
            addView(next, LayoutParams(dp(64), dp(64)))
        }
        addView(transport, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        addView(speed, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(20)
        })
        addView(View(context), LayoutParams(MATCH_PARENT, 0, 1f))
        update(MediaState())
    }

    fun update(state: MediaState) {
        val failed = state.error != null
        message.visibility = if (failed) VISIBLE else GONE
        openWith.visibility = if (failed) VISIBLE else GONE
        seek.visibility = if (failed) GONE else VISIBLE
        transport.visibility = if (failed) GONE else VISIBLE
        speed.visibility = if (failed) GONE else VISIBLE
        if (failed) message.text = state.error
        seek.update(state)
        if (shownPlaying != state.playing) {
            shownPlaying = state.playing
            playButton.setImageDrawable(Pixel.icon(context, if (state.playing) PixelIcon.PAUSE else PixelIcon.PLAY, Pixel.BACKGROUND, 40))
            playButton.contentDescription = if (state.playing) "Pause" else "Play"
        }
        enable(previous, state.hasPrevious || state.position > 0)
        enable(next, state.hasNext)
        speed.text = speedLabel(state.speed)
    }

    /** A line under the controls (why Open with didn't). */
    fun note(text: String) {
        message.text = text
        message.visibility = VISIBLE
    }

    // Short screens (landscape) leave the note out.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tall = MeasureSpec.getSize(heightMeasureSpec) >= dp(600) || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
        val wanted = if (tall) VISIBLE else GONE
        if (art.visibility != wanted) art.visibility = wanted
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun enable(button: View, on: Boolean) {
        button.isEnabled = on
        button.alpha = if (on) 1f else 0.35f
    }

    private fun sideParams() = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        leftMargin = dp(24)
        rightMargin = dp(24)
    }

    private fun dp(value: Int) = Pixel.dp(context, value)
}

/**
 * The video's controls over the picture: a top bar (←, the name), ⟲10,
 * play/pause and 10⟳ in the middle, the seek bar and times at the
 * bottom. Taps between them fall through to the picture (show/hide,
 * double-tap seeks). The screen pads it clear of the bars.
 */
@SuppressLint("ViewConstructor")
class VideoControlsView(context: Context, name: String, private val controls: MediaControls) : FrameLayout(context) {
    private val seek = MediaSeek(context, controls, light = true)
    private val middle: LinearLayout
    private val playButton: ImageView
    private val speed: TextView
    private val message: TextView
    private val openWith: TextView
    private var shownPlaying: Boolean? = null

    init {
        val shade = 0xB3000000.toInt()
        speed = mediaTextButton(context, speedLabel(1f), main = false, light = true) {
            controls.cycleSpeed()
            controls.touched()
        }.apply { contentDescription = "Speed" }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(shade)
            isClickable = true
            addView(mediaIconButton(context, PixelIcon.BACK, Pixel.CYAN, "Back", 20) { controls.back() },
                LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(context).apply {
                text = name
                setTextColor(Color.WHITE)
                typeface = Pixel.label(context, 700)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(0, 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(speed, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        }, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        playButton = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            background = Pixel.box(context, fill = shade, border = 0, ripple = Pixel.LINE)
            contentDescription = "Play"
            isFocusable = true
            setOnClickListener {
                controls.toggle()
                controls.touched()
            }
        }
        middle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(skipButton(context, back = true), LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(playButton, LinearLayout.LayoutParams(dp(80), dp(80)).apply {
                leftMargin = dp(32)
                rightMargin = dp(32)
            })
            addView(skipButton(context, back = false), LinearLayout.LayoutParams(dp(72), dp(72)))
        }
        addView(middle, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        message = TextView(context).apply {
            setTextColor(Color.WHITE)
            typeface = Pixel.text(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
        }
        openWith = mediaTextButton(context, "Open with another app", main = true) { controls.openWith() }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(message, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(openWith, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        }, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        seek.setBackgroundColor(shade)
        seek.isClickable = true
        addView(seek, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        update(MediaState())
    }

    fun update(state: MediaState) {
        val failed = state.error != null
        message.visibility = if (failed) VISIBLE else GONE
        openWith.visibility = if (failed) VISIBLE else GONE
        middle.visibility = if (failed) GONE else VISIBLE
        seek.visibility = if (failed) GONE else VISIBLE
        if (failed) message.text = state.error
        seek.update(state)
        if (shownPlaying != state.playing) {
            shownPlaying = state.playing
            playButton.setImageDrawable(Pixel.icon(context, if (state.playing) PixelIcon.PAUSE else PixelIcon.PLAY, Color.WHITE, 40))
            playButton.contentDescription = if (state.playing) "Pause" else "Play"
        }
        speed.text = speedLabel(state.speed)
    }

    /** A line over the picture (why Open with didn't). */
    fun note(text: String) {
        message.text = text
        message.visibility = VISIBLE
    }

    // ⟲10 / 10⟳: the loop with "10" beside it.
    private fun skipButton(context: Context, back: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = pixelRipple()
        contentDescription = if (back) "Back 10 seconds" else "Forward 10 seconds"
        isClickable = true
        isFocusable = true
        setOnClickListener {
            controls.skip(if (back) -SEEK_STEP_MS else SEEK_STEP_MS)
            controls.touched()
        }
        addView(ImageView(context).apply {
            setImageDrawable(Pixel.icon(context, if (back) PixelIcon.REPLAY else PixelIcon.REFRESH, Color.WHITE, 32))
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        addView(TextView(context).apply {
            text = "10"
            setTextColor(Color.WHITE)
            typeface = Pixel.label(context, 600)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    private fun dp(value: Int) = Pixel.dp(context, value)
}

// The seek bar with the time played on its left and the length on its right; [light] on video.
@SuppressLint("ViewConstructor")
private class MediaSeek(context: Context, private val controls: MediaControls, light: Boolean) : LinearLayout(context) {
    private val played = time(context, light)
    private val length = time(context, light)
    private val bar = SeekBar(context)
    private var dragging = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = Pixel.dp(context, 8)
        setPadding(pad, pad, pad, pad)
        bar.progressTintList = ColorStateList.valueOf(Pixel.CYAN)
        bar.progressBackgroundTintList = ColorStateList.valueOf(if (light) Color.GRAY else Pixel.LINE)
        bar.thumbTintList = ColorStateList.valueOf(Pixel.PINK)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) played.text = mediaTime(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                dragging = true
                controls.touched()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                controls.seekTo(seekBar.progress.toLong())
                controls.touched()
            }
        })
        addView(played, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(bar, LayoutParams(0, WRAP_CONTENT, 1f))
        addView(length, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    fun update(state: MediaState) {
        if (dragging) return
        val duration = state.duration.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        bar.isEnabled = duration > 0
        bar.max = maxOf(duration, 1)
        bar.progress = state.position.coerceIn(0, duration.toLong()).toInt()
        played.text = mediaTime(state.position)
        length.text = if (duration > 0) mediaTime(state.duration) else "–:––"
    }

    private fun time(context: Context, light: Boolean) = TextView(context).apply {
        setTextColor(if (light) Color.WHITE else Pixel.DIM)
        typeface = Pixel.text(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        minWidth = Pixel.dp(context, 44)
        gravity = Gravity.CENTER
    }
}

private fun mediaIconButton(context: Context, icon: PixelIcon, color: Int, description: String, sizeDp: Int, onTap: () -> Unit) =
    ImageView(context).apply {
        setImageDrawable(Pixel.icon(context, icon, color, sizeDp))
        scaleType = ImageView.ScaleType.CENTER
        background = pixelRipple()
        contentDescription = description
        isFocusable = true
        setOnClickListener { onTap() }
    }

// As Files' buttons: the main one filled cyan, the other boxed; [light] boxes on video.
private fun mediaTextButton(context: Context, label: String, main: Boolean, light: Boolean = false, onTap: () -> Unit) =
    TextView(context).apply {
        text = label
        typeface = Pixel.label(context, 600)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (main) Pixel.BACKGROUND else if (light) Color.WHITE else Pixel.TEXT)
        gravity = Gravity.CENTER
        maxLines = 1
        minHeight = Pixel.dp(context, 44)
        val pad = Pixel.dp(context, 14)
        setPadding(pad, 0, pad, 0)
        background = when {
            main -> Pixel.box(context, fill = Pixel.CYAN, border = 0, ripple = Pixel.RIPPLE_ON_CYAN)
            light -> Pixel.box(context, fill = Color.TRANSPARENT, border = Color.WHITE, ripple = Pixel.LINE)
            else -> Pixel.box(context, fill = Pixel.CARD, border = Pixel.LINE, ripple = Pixel.LINE)
        }
        isFocusable = true
        setOnClickListener { onTap() }
    }

// Holds the video's surface at the picture's shape, centred (core's videoFit).
private class VideoFrame(context: Context) : FrameLayout(context) {
    private var videoW = 0
    private var videoH = 0

    fun setVideoSize(w: Int, h: Int) {
        if (w == videoW && h == videoH) return
        videoW = w
        videoH = h
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val (fitW, fitH) = videoFit(w, h, videoW, videoH)
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(fitW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(fitH, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val x = (width - child.measuredWidth) / 2
            val y = (height - child.measuredHeight) / 2
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}

// Taps on the picture: one shows or hides the controls, two seek by the half they hit.
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class TouchPad(context: Context, private val tap: () -> Unit, private val doubleTap: (Float, Float) -> Unit) : View(context) {
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            tap()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            doubleTap(e.x, width.toFloat())
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)
}
