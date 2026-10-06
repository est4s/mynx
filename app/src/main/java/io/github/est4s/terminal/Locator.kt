package io.github.est4s.terminal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import io.github.est4s.terminal.core.Fix
import io.github.est4s.terminal.core.LocationAccess
import io.github.est4s.terminal.core.LocationQuery
import io.github.est4s.terminal.core.LocationReport
import io.github.est4s.terminal.core.locationProviders

/**
 * Answers `pc26 location` from Android's LocationManager, on the main
 * thread. [onScreen] says whether the app is in use; Android only gives
 * "while in use" location to it, or to a service already locating in
 * the foreground. [askPermission] shows Android's dialog and calls back
 * with whether location was allowed, or returns false when it can't ask.
 * [locatingChanged] says whether anything is locating, so the service
 * can add or drop the location foreground type.
 */
class Locator(
    private val context: Context,
    private val handler: Handler,
    private val onScreen: () -> Boolean,
    private val askPermission: ((Boolean) -> Unit) -> Boolean,
    private val locatingChanged: (Boolean) -> Unit,
) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val jobs = mutableSetOf<Job>()

    fun locate(query: LocationQuery, report: LocationReport) {
        val job = Job(query, report)
        report.onCancel { handler.post { job.stop() } }
        if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return job.start()
        val asked = askPermission { allowed ->
            if (allowed) job.start() else report.fail("location wasn't allowed (allow it in the app's Android settings)")
        }
        if (!asked) report.fail("the app must be on screen the first time, so Android can ask to allow location")
    }

    fun stopAll() {
        jobs.toList().forEach { it.stop() }
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun access() = LocationAccess(
        precise = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        approximate = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
        locationOn = if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
        else manager.getProviders(true).isNotEmpty(),
        hasGps = LocationManager.GPS_PROVIDER in manager.allProviders,
        hasNetwork = LocationManager.NETWORK_PROVIDER in manager.allProviders,
    )

    private inner class Job(private val query: LocationQuery, private val report: LocationReport) : LocationListener {
        private var stopped = false
        private val timeout = Runnable {
            report.fail("no fix within ${query.timeoutSeconds} s")
            stop()
        }

        fun start() {
            if (stopped) return
            val providers = locationProviders(query, access()).getOrElse { return fail(it.message!!) }
            if (!onScreen() && jobs.isEmpty()) {
                return fail("the app must be on screen to start (Android only gives location to the app in use)")
            }
            val interval = if (query.stream) query.intervalSeconds * 1000L else 0L
            try {
                providers.forEach { manager.requestLocationUpdates(it, interval, 0f, this, handler.looper) }
            } catch (e: SecurityException) {
                return fail("location wasn't allowed (allow it in the app's Android settings)")
            } catch (e: IllegalArgumentException) {
                return fail("couldn't start locating: ${e.message}")
            }
            jobs += this
            if (jobs.size == 1) locatingChanged(true)
            if (!query.stream) handler.postDelayed(timeout, query.timeoutSeconds * 1000L)
        }

        fun stop() {
            if (stopped) return
            stopped = true
            handler.removeCallbacks(timeout)
            manager.removeUpdates(this)
            if (jobs.remove(this) && jobs.isEmpty()) locatingChanged(false)
        }

        private fun fail(message: String) {
            report.fail(message)
            stop()
        }

        override fun onLocationChanged(location: Location) {
            if (stopped) return
            report.fix(location.toFix())
            if (!query.stream) stop()
        }

        // Before API 30 these have no default bodies, so Android would crash calling them.
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Not called on API 29+")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
}

private fun Location.toFix() = Fix(
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy.takeIf { hasAccuracy() },
    altitude = altitude.takeIf { hasAltitude() },
    speed = speed.takeIf { hasSpeed() },
    bearing = bearing.takeIf { hasBearing() },
    provider = provider ?: "unknown",
    time = time,
)
