package io.github.est4s.terminal

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import io.github.est4s.terminal.core.PlayQuery
import io.github.est4s.terminal.core.PlayReport
import java.io.FileInputStream

/** Answers `pc26 audio play` with Android's MediaPlayer, on [handler]'s thread. */
class AudioPlayer(private val handler: Handler) {
    private val players = mutableSetOf<MediaPlayer>()

    fun play(query: PlayQuery, report: PlayReport) {
        handler.post { start(query, report) }
    }

    fun stopAll() {
        handler.post { players.toList().forEach { release(it) } }
    }

    private fun start(query: PlayQuery, report: PlayReport) {
        val player = MediaPlayer()
        players += player
        report.onCancel { handler.post { release(player) } }
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        player.setOnPreparedListener { it.start() }
        player.setOnCompletionListener {
            val seconds = it.duration / 1000.0
            release(it)
            report.done(seconds)
        }
        player.setOnErrorListener { mp, _, _ ->
            release(mp)
            report.fail("couldn't play ${query.path}")
            true
        }
        try {
            // The media server can't open files in the app's storage itself.
            FileInputStream(query.file).use { player.setDataSource(it.fd) }
            player.prepareAsync()
        } catch (e: Exception) {
            release(player)
            report.fail("couldn't play ${query.path}: not a sound file Android can play")
        }
    }

    private fun release(player: MediaPlayer) {
        if (players.remove(player)) player.release()
    }
}
