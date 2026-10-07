package io.github.est4s.terminal

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.est4s.terminal.core.SensorQuery
import io.github.est4s.terminal.core.SensorReading
import io.github.est4s.terminal.core.SensorReport

/**
 * Answers `mynx sensor` from Android's SensorManager. Readings arrive
 * on a thread of their own (up to 200 a second); [read] is called on
 * the main thread. [askPermission] shows Android's dialog for the
 * permissions a sensor needs beyond the app's own (step counting), and
 * calls back with whether they were allowed, or returns false when it
 * can't ask (the app isn't on screen).
 */
class SensorReader(
    private val context: Context,
    private val askPermission: (Array<String>, (Boolean) -> Unit) -> Boolean,
) {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val handler by lazy { Handler(HandlerThread("sensors").apply { start() }.looper) }
    private val jobs = mutableSetOf<Job>()

    fun hasType(type: Int) = manager.getDefaultSensor(type) != null

    fun read(query: SensorQuery, report: SensorReport) {
        val job = Job(query, report)
        report.onCancel { handler.post { job.stop() } }
        // Step counting needs no permission before Android 10.
        val permission = query.kind.permission.takeIf { Build.VERSION.SDK_INT >= 29 }
        if (permission == null || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            handler.post { job.start() }
            return
        }
        val asked = askPermission(arrayOf(permission)) { allowed ->
            if (allowed) handler.post { job.start() }
            else report.fail("${query.kind.name} wasn't allowed (allow physical activity in the app's Android settings)")
        }
        if (!asked) report.fail("the app must be on screen the first time, so Android can ask to allow physical activity")
    }

    fun stopAll() {
        handler.post { jobs.toList().forEach { it.stop() } }
    }

    private inner class Job(private val query: SensorQuery, private val report: SensorReport) : SensorEventListener {
        private var stopped = false
        private val timeout = Runnable {
            report.fail("no reading within ${query.timeoutSeconds} s")
            stop()
        }

        fun start() {
            if (stopped) return
            val sensor = manager.getDefaultSensor(query.kind.type)
                ?: return fail("the phone has no ${query.kind.name} sensor")
            if (!manager.registerListener(this, sensor, query.periodMicros, handler)) {
                return fail("couldn't start reading the ${query.kind.name} sensor")
            }
            jobs += this
            if (!query.stream) handler.postDelayed(timeout, query.timeoutSeconds * 1000L)
        }

        fun stop() {
            if (stopped) return
            stopped = true
            handler.removeCallbacks(timeout)
            manager.unregisterListener(this)
            jobs.remove(this)
        }

        private fun fail(message: String) {
            report.fail(message)
            stop()
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (stopped) return
            // Event times count from boot; mynx shows the clock time.
            val ago = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
            report.reading(SensorReading(event.values.toList(), event.accuracy, System.currentTimeMillis() - ago))
            if (!query.stream) stop()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }
}
