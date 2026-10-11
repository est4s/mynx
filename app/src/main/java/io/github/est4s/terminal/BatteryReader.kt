package io.github.est4s.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import io.github.est4s.terminal.core.BatteryQuery
import io.github.est4s.terminal.core.BatteryReading
import io.github.est4s.terminal.core.BatteryReport

/**
 * Answers `mynx battery` from Android's battery broadcast,
 * BatteryManager and PowerManager. None of it needs a permission. A
 * stream listens for changes to the battery, battery saver and the
 * thermal status, and with an interval also reads that often; all on
 * [handler]'s thread.
 */
class BatteryReader(private val context: Context, private val handler: Handler) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val jobs = mutableSetOf<Job>()

    fun read(query: BatteryQuery, report: BatteryReport) {
        val job = Job(query, report)
        report.onCancel { handler.post { job.stop() } }
        handler.post { job.start() }
    }

    // At once on the handler's thread: the service unregisters before it's destroyed.
    fun stopAll() {
        val stop = Runnable { jobs.toList().forEach { it.stop() } }
        if (Looper.myLooper() == handler.looper) stop.run() else handler.post(stop)
    }

    /** What Android says now; [sticky] is the last battery broadcast, if known. */
    private fun reading(sticky: Intent?): BatteryReading? {
        val intent = sticky ?: register(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        fun extra(name: String) = intent.getIntExtra(name, -1)
        fun property(id: Int) = battery?.getIntProperty(id)
        return BatteryReading(
            level = extra(BatteryManager.EXTRA_LEVEL),
            scale = extra(BatteryManager.EXTRA_SCALE),
            status = extra(BatteryManager.EXTRA_STATUS),
            plugged = extra(BatteryManager.EXTRA_PLUGGED),
            health = extra(BatteryManager.EXTRA_HEALTH),
            temperature = intent.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            voltage = intent.takeIf { it.hasExtra(BatteryManager.EXTRA_VOLTAGE) }
                ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            current = property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            currentAverage = property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            charge = property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            chargeTimeMs = if (Build.VERSION.SDK_INT >= 28) battery?.computeChargeTimeRemaining() else null,
            cycles = if (Build.VERSION.SDK_INT >= 34) extra(BatteryManager.EXTRA_CYCLE_COUNT) else null,
            powerSaver = power?.isPowerSaveMode == true,
            thermal = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null,
            time = System.currentTimeMillis(),
        )
    }

    // Battery broadcasts come from the system, which reaches unexported receivers too.
    private fun register(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, handler)
        }

    private inner class Job(private val query: BatteryQuery, private val report: BatteryReport) {
        private var stopped = false
        private var last: Intent? = null
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) last = intent
                send()
            }
        }
        // A PowerManager.OnThermalStatusChangedListener, on Android 10 and up.
        private var thermal: Any? = null
        private val tick = object : Runnable {
            override fun run() {
                send()
                query.intervalSeconds?.let { handler.postDelayed(this, it * 1000L) }
            }
        }

        fun start() {
            if (stopped) return
            if (!query.stream) {
                reading(null)?.let(report::reading) ?: report.fail("Android didn't say how the battery is")
                return
            }
            jobs += this
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            // The battery broadcast is sticky: registering gives the last one, and
            // sends it to the receiver too, which is the stream's first line.
            last = register(receiver, filter)
            if (Build.VERSION.SDK_INT >= 29) {
                val listener = PowerManager.OnThermalStatusChangedListener { send() }
                thermal = listener
                power?.addThermalStatusListener({ handler.post(it) }, listener)
            }
            query.intervalSeconds?.let { handler.postDelayed(tick, it * 1000L) }
        }

        fun stop() {
            if (stopped) return
            stopped = true
            if (jobs.remove(this)) {
                runCatching { context.unregisterReceiver(receiver) }
                if (Build.VERSION.SDK_INT >= 29) {
                    (thermal as? PowerManager.OnThermalStatusChangedListener)?.let { power?.removeThermalStatusListener(it) }
                }
            }
            handler.removeCallbacks(tick)
        }

        private fun send() {
            if (!stopped) reading(last)?.let(report::reading)
        }
    }
}
