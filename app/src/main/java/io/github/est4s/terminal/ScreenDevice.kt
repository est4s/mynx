package io.github.est4s.terminal

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import io.github.est4s.terminal.core.FoundScreenHost
import io.github.est4s.terminal.core.SCREEN_SERVICE_TYPE
import io.github.est4s.terminal.core.ScreenAd
import io.github.est4s.terminal.core.ScreenMessage
import io.github.est4s.terminal.core.ScreenPlatform
import io.github.est4s.terminal.core.foundScreenHost

/**
 * The extended screen's Android side (core's `ScreenLink` does the
 * rest): this phone's screen, and mDNS through NsdManager, which
 * advertises the host and finds hosts for a screen.
 */
class ScreenDevice(private val context: Context, private val changed: () -> Unit = {}) : ScreenPlatform {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registration: NsdManager.RegistrationListener? = null

    @Suppress("DEPRECATION") // getRealMetrics: the whole screen, which WindowMetrics gives only for windows.
    override fun screen(): ScreenMessage.ScreenInfo {
        val display: Display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return ScreenMessage.ScreenInfo(0, 0, 0, 0)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return ScreenMessage.ScreenInfo(
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, display.rotation, metrics.xdpi, metrics.ydpi,
        )
    }

    @Synchronized
    override fun advertise(ad: ScreenAd?, port: Int) {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        if (ad == null) return
        val info = NsdServiceInfo()
        info.serviceName = ad.name
        info.serviceType = SCREEN_SERVICE_TYPE
        info.port = port
        ad.txt.forEach { (key, value) -> info.setAttribute(key, value) }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    // resolveService and NsdServiceInfo.host: their replacements need API 34.
    @Suppress("DEPRECATION")
    override fun discover(ms: Long, found: (List<FoundScreenHost>) -> Unit) {
        val hosts = mutableListOf<FoundScreenHost>()
        val toResolve = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        var done = false
        // NsdManager resolves one service at a time; the rest wait their turn.
        fun next() {
            if (done || resolving) return
            val service = toResolve.removeFirstOrNull() ?: return
            resolving = true
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                    handler.post { resolving = false; next() }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val address = info.host?.hostAddress
                    val txt = info.attributes.mapValues { (_, v) -> v?.let { String(it) } }
                    handler.post {
                        if (address != null) foundScreenHost(info.serviceName, txt, address, info.port)?.let { hosts += it }
                        resolving = false
                        next()
                    }
                }
            })
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, error: Int) {}
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                handler.post { toResolve += info; next() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        val started = runCatching { nsd.discoverServices(SCREEN_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
        handler.postDelayed({
            done = true
            if (started) runCatching { nsd.stopServiceDiscovery(listener) }
            found(hosts.toList())
        }, ms)
    }

    override fun changed() = changed.invoke()
}
