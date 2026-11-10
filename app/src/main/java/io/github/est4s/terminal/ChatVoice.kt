package io.github.est4s.terminal

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast

/** Chat's mic: dictation into the box. */
interface ChatDictation {
    /** Whether the phone can dictate at all; the mic is hidden when not. */
    val available: Boolean

    /**
     * Starts listening: [text] gets what's heard so far (final = false) and
     * then the whole of it (final = true); [listening] says when it starts
     * and stops. Main thread.
     */
    fun start(text: (String, Boolean) -> Unit, listening: (Boolean) -> Unit)

    /** Stops listening; what was heard still comes as the final text. */
    fun stop()
}

/**
 * Dictation with Android's [SpeechRecognizer] (RECORD_AUDIO asked through
 * the activity), shown in the box as it's heard. Phones without a
 * recognizer service but with a speech activity get that activity
 * instead, which answers once at the end.
 */
class SpeechDictation(private val activity: Activity, private val asks: ActivityAsks) : ChatDictation {
    private var recognizer: SpeechRecognizer? = null
    private var listeningNow: ((Boolean) -> Unit)? = null

    private val service get() = SpeechRecognizer.isRecognitionAvailable(activity)

    private val speechActivity get() = speechIntent().resolveActivity(activity.packageManager) != null

    override val available: Boolean get() = service || speechActivity

    override fun start(text: (String, Boolean) -> Unit, listening: (Boolean) -> Unit) {
        when {
            service -> {
                if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    listen(text, listening)
                } else {
                    asks.askPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)) { allowed ->
                        if (allowed) listen(text, listening) else toast("The microphone wasn't allowed (allow it in the app's Android settings)")
                    }
                }
            }
            speechActivity -> {
                val started = asks.startForData(speechIntent()) { ok, data ->
                    val heard = if (ok) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null
                    heard?.let { text(it, true) }
                }
                if (!started) toast("No speech recognition on the phone")
            }
            else -> toast("No speech recognition on the phone")
        }
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    private fun listen(text: (String, Boolean) -> Unit, listening: (Boolean) -> Unit) {
        finish()
        val heard = SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = heard
        listeningNow = listening
        heard.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                firstOf(partialResults)?.let { text(it, false) }
            }

            override fun onResults(results: Bundle?) {
                firstOf(results)?.let { text(it, true) }
                if (recognizer === heard) finish()
            }

            override fun onError(error: Int) {
                if (recognizer !== heard) return
                finish()
                when (error) {
                    // Nothing said, or stopped before anything was: nothing to tell.
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> Unit
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> toast("The microphone wasn't allowed")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> toast("Speech recognition needs the network")
                    else -> toast("Speech recognition stopped (error $error)")
                }
            }
        })
        listening(true)
        heard.startListening(speechIntent().putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
    }

    /** Ends the recognizer in use, if any, and says it stopped listening. */
    fun finish() {
        val done = recognizer ?: return
        recognizer = null
        done.destroy()
        listeningNow?.invoke(false)
        listeningNow = null
    }

    private fun firstOf(results: Bundle?) =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun speechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
