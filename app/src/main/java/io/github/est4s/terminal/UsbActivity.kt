package io.github.est4s.terminal

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Sent to MainActivity when a USB device was allowed through Android's dialog. */
const val ACTION_USB_ALLOWED = "io.github.est4s.terminal.USB_ALLOWED"

/**
 * Android's "open mynx when this is connected?" for USB serial devices
 * (res/xml/usb_serial_devices.xml). Saying yes allows the device; the
 * app then opens, and its USB bridge serves the ports when it comes on
 * screen (or at once, if it's on screen already). Shows nothing itself.
 */
class UsbActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_USB_ALLOWED)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
