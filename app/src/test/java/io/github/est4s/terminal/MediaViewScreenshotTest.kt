package io.github.est4s.terminal

import android.app.Application
import android.graphics.Color
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val noMediaControls = object : MediaControls {
    override fun back() = Unit
    override fun toggle() = Unit
    override fun seekTo(ms: Long) = Unit
    override fun skip(delta: Long) = Unit
    override fun step(direction: Int) = Unit
    override fun cycleSpeed() = Unit
    override fun openWith() = Unit
}

private val PLAYING = MediaState(
    path = "/root/Music/field-recordings/rain-on-the-tin-roof.ogg",
    playing = true,
    position = 83_000,
    duration = 245_000,
    speed = 1.25f,
    hasPrevious = true,
    hasNext = true,
    loading = false,
)

/** The media player's screens, drawn from a [MediaState] without a player: see [Screenshots]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class MediaViewScreenshotTest {
    private fun audio(w: Int, h: Int, name: String, state: MediaState) {
        val view = Screenshots.open(w, h) { activity ->
            AudioPlayerView(activity, state.path.substringAfterLast('/'), "~/Music/field-recordings", noMediaControls)
        }
        view.update(state)
        Screenshots.save(view, name)
    }

    // The controls over a stand-in for the picture: a grey 16:9 block on black.
    private fun video(w: Int, h: Int, name: String, state: MediaState) {
        var controls: VideoControlsView? = null
        val view = Screenshots.open(w, h) { activity ->
            FrameLayout(activity).apply {
                setBackgroundColor(Color.BLACK)
                val pictureH = minOf(w * 9 / 16, h)
                addView(android.view.View(activity).apply { setBackgroundColor(Color.rgb(60, 70, 90)) },
                    FrameLayout.LayoutParams(pictureH * 16 / 9, pictureH, android.view.Gravity.CENTER))
                val overlay = VideoControlsView(activity, "holiday-2026-08-14.mp4", noMediaControls)
                controls = overlay
                addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        }
        controls?.update(state)
        Screenshots.save(view, name)
    }

    @Test
    fun audioPlaying() = audio(1080, 2400, "media-audio", PLAYING)

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun audioNarrow() = audio(945, 2100, "media-audio-360dp", PLAYING)

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun audioLandscape() = audio(2400, 1080, "media-audio-landscape", PLAYING.copy(playing = false, speed = 1f))

    @Test
    fun audioCantPlay() = audio(
        1080, 2400, "media-audio-error",
        MediaState(path = "/root/Music/song.mid", loading = false, error = "Android can't play song.mid (error 1, -1010)."),
    )

    @Test
    fun videoControls() = video(1080, 2400, "media-video", PLAYING.copy(playing = false, position = 12_000, duration = 3_723_000, speed = 1f))

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun videoLandscape() = video(2400, 1080, "media-video-landscape", PLAYING.copy(position = 12_000, duration = 94_000, speed = 1f))
}
