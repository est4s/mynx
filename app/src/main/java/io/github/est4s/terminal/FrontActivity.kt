package io.github.est4s.terminal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.Surface
import java.lang.ref.WeakReference

private const val PERMISSION_REQUEST = 2
private const val RESULT_REQUEST = 3

/**
 * What the service asks of the activity in front: the terminal's
 * (`MainActivity`) or an app's screen (`WebAppActivity`). Android's
 * permission dialogs, the camera app and the share sheet need one.
 */
interface FrontActivity {
    val activity: Activity
    /** Between onStart and onStop. */
    val onScreen: Boolean
    /** How far the screen is turned, in degrees, for photos taken now. */
    val displayRotation: Int
    /** Shows Android's dialog for [permissions]; [answer] gets whether any of them was allowed. */
    fun askPermissions(permissions: Array<String>, answer: (Boolean) -> Unit)
    /** Starts [intent] (the camera app); [answer] gets whether it finished OK. False if nothing could start it. */
    fun startForResult(intent: Intent, answer: (Boolean) -> Unit): Boolean
}

/** The activities that can be in front, the last one resumed first. Main thread only. */
object FrontActivities {
    private val known = mutableListOf<WeakReference<FrontActivity>>()

    fun resumed(front: FrontActivity) {
        known.removeAll { it.get() == null || it.get() === front }
        known.add(0, WeakReference(front))
    }

    fun gone(front: FrontActivity) {
        known.removeAll { it.get() == null || it.get() === front }
    }

    /** The activity in front: the last resumed one still on screen, else any on screen. */
    val current: FrontActivity? get() = known.firstNotNullOfOrNull { ref -> ref.get()?.takeIf { it.onScreen } }
}

/**
 * Android's permission dialogs, one at a time in order, and one
 * activity result at a time, for a [FrontActivity]. The activity passes
 * on its `onRequestPermissionsResult` and `onActivityResult`.
 */
class ActivityAsks(private val activity: Activity) {
    private val permissionAsks = mutableListOf<Pair<Array<String>, (Boolean) -> Unit>>()
    private var resultAnswer: ((Boolean, Intent?) -> Unit)? = null

    fun askPermissions(permissions: Array<String>, answer: (Boolean) -> Unit) {
        permissionAsks += permissions to answer
        if (permissionAsks.size == 1) activity.requestPermissions(permissions, PERMISSION_REQUEST)
    }

    fun startForResult(intent: Intent, answer: (Boolean) -> Unit): Boolean = startForData(intent) { ok, _ -> answer(ok) }

    /** As [startForResult], with what it sent back (a picker's files). */
    @Suppress("DEPRECATION") // the ActivityResult API needs AndroidX
    fun startForData(intent: Intent, answer: (Boolean, Intent?) -> Unit): Boolean {
        resultAnswer?.invoke(false, null)
        resultAnswer = null
        return try {
            activity.startActivityForResult(intent, RESULT_REQUEST)
            resultAnswer = answer
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent? = null) {
        if (requestCode != RESULT_REQUEST) return
        val answer = resultAnswer ?: return
        resultAnswer = null
        answer(resultCode == Activity.RESULT_OK, data)
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != PERMISSION_REQUEST || permissionAsks.isEmpty()) return
        val answer = permissionAsks.removeAt(0).second
        permissionAsks.firstOrNull()?.let { activity.requestPermissions(it.first, PERMISSION_REQUEST) }
        answer(grantResults.any { it == PackageManager.PERMISSION_GRANTED })
    }

    /** The activity is going: everything waiting gets no. */
    fun clear() {
        permissionAsks.toList().also { permissionAsks.clear() }.forEach { it.second(false) }
        resultAnswer?.let {
            resultAnswer = null
            it(false, null)
        }
    }

    val displayRotation: Int
        get() {
            @Suppress("DEPRECATION") // display needs API 30
            val rotation = if (Build.VERSION.SDK_INT >= 30) activity.display?.rotation else activity.windowManager.defaultDisplay.rotation
            return when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        }
}
