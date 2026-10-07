package io.github.est4s.terminal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import io.github.est4s.terminal.core.Facing
import io.github.est4s.terminal.core.PhotoQuery
import io.github.est4s.terminal.core.PhotoReport
import io.github.est4s.terminal.core.jpegOrientation
import io.github.est4s.terminal.core.shotReady
import io.github.est4s.terminal.core.torchLevel
import java.io.File
import java.util.UUID

// A quick shot that hasn't happened by then won't.
private const val QUICK_SHOT_MS = 15_000L

/**
 * Answers `mynx camera` and `mynx torch`. A photo goes to a
 * temporary file in the app's cache first; core moves it into Debian.
 * [activity] is the activity while it's on screen: Android only lets the
 * app in front open the camera app or a camera. Called on the main thread.
 */
class CameraShooter(
    private val context: Context,
    private val activity: () -> MainActivity?,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val handler by lazy { Handler(HandlerThread("camera").apply { start() }.looper) }
    private val photos by lazy { File(context.cacheDir, "photos").apply { mkdirs() } }

    fun take(query: PhotoQuery, report: PhotoReport) {
        val shown = activity()
            ?: return report.fail("the app must be on screen to take a photo (Android only lets the app in front use the camera)")
        // With CAMERA in the manifest, Android refuses even the camera app
        // to an app that hasn't been allowed it.
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            start(shown, query, report)
            return
        }
        shown.askPermissions(arrayOf(Manifest.permission.CAMERA)) { allowed ->
            val now = activity()
            when {
                !allowed -> report.fail("the camera wasn't allowed (allow it in the app's Android settings)")
                now == null -> report.fail("the app must be on screen to take a photo")
                else -> start(now, query, report)
            }
        }
    }

    private fun start(shown: MainActivity, query: PhotoQuery, report: PhotoReport) {
        val file = File(photos, "${UUID.randomUUID()}.jpg")
        val facing = query.quick
        if (facing == null) {
            withCameraApp(shown, file, report)
        } else {
            val rotation = shown.displayRotation
            val shot = QuickShot(facing, rotation, file, report)
            report.onCancel { handler.post { shot.close() } }
            handler.post { shot.start() }
        }
    }

    private fun withCameraApp(shown: MainActivity, file: File, report: PhotoReport) {
        file.createNewFile()
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, PhotoProvider.uriFor(file))
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val started = shown.startForResult(intent) { ok ->
            if (ok && file.length() > 0) {
                report.taken(file)
            } else {
                file.delete()
                report.fail("no photo was taken")
            }
        }
        if (!started) {
            file.delete()
            report.fail("no camera app on the phone")
        }
    }

    /** Turns the flashlight on (at [percent] strength, else the phone's default) or off: null, or why not. */
    fun torch(on: Boolean, percent: Int?): String? {
        val id = runCatching {
            manager.cameraIdList.filter { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                .minByOrNull { if (facingOf(it) == CameraCharacteristics.LENS_FACING_BACK) 0 else 1 }
        }.getOrNull() ?: return "the phone has no flashlight"
        return try {
            if (on && percent != null) {
                val max = if (Build.VERSION.SDK_INT >= 33) {
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                } else 1
                if (Build.VERSION.SDK_INT < 33 || max <= 1) {
                    return "the phone's flashlight has no strength levels (mynx torch on)"
                }
                manager.turnOnTorchWithStrengthLevel(id, torchLevel(percent, max))
            } else {
                manager.setTorchMode(id, on)
            }
            null
        } catch (e: CameraAccessException) {
            "the flashlight is busy (is the camera in use?)"
        } catch (e: IllegalArgumentException) {
            e.message ?: "the flashlight refused"
        }
    }

    private fun facingOf(id: String) = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)

    /**
     * One photo straight from a camera: a small preview stream lets
     * exposure and focus settle, then a full-size JPEG. Runs on [handler].
     */
    private inner class QuickShot(
        private val facing: Facing,
        private val displayRotation: Int,
        private val file: File,
        private val report: PhotoReport,
    ) {
        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var preview: ImageReader? = null
        private var jpeg: ImageReader? = null
        private var closed = false
        private var shooting = false
        private var began = 0L
        private val timeout = Runnable { fail("the camera didn't take a photo within ${QUICK_SHOT_MS / 1000} s") }

        @SuppressLint("MissingPermission") // checked in take()
        fun start() {
            val want = if (facing == Facing.FRONT) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val side = facing.name.lowercase()
            try {
                val id = manager.cameraIdList.firstOrNull { facingOf(it) == want } ?: return fail("the phone has no $side camera")
                val chars = manager.getCameraCharacteristics(id)
                val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return fail("the $side camera can't take photos")
                val full = sizes.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height }
                    ?: return fail("the $side camera can't take JPEG photos")
                val small = sizes.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width >= 320 }
                    .minByOrNull { it.width.toLong() * it.height } ?: return fail("the $side camera has no preview")
                jpeg = ImageReader.newInstance(full.width, full.height, ImageFormat.JPEG, 1).apply {
                    setOnImageAvailableListener({ reader -> saved(reader) }, handler)
                }
                preview = ImageReader.newInstance(small.width, small.height, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() }, handler)
                }
                handler.postDelayed(timeout, QUICK_SHOT_MS)
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        if (closed) return close()
                        configure(camera, chars)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        device = camera
                        fail("the $side camera was taken by another app")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        device = camera
                        fail("the $side camera failed (error $error)")
                    }
                }, handler)
            } catch (e: CameraAccessException) {
                fail("couldn't open the $side camera: ${e.message}")
            } catch (e: SecurityException) {
                fail("the camera wasn't allowed (allow it in the app's Android settings)")
            }
        }

        @Suppress("DEPRECATION") // the SessionConfiguration version needs API 28
        private fun configure(camera: CameraDevice, chars: CameraCharacteristics) {
            val previewSurface = preview!!.surface
            val jpegSurface = jpeg!!.surface
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
            val autofocus = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes
            val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            fun CaptureRequest.Builder.auto() = apply {
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                if (autofocus) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            camera.createCaptureSession(listOf(previewSurface, jpegSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    if (closed) return close()
                    began = SystemClock.elapsedRealtime()
                    val settle = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).auto().apply {
                        addTarget(previewSurface)
                    }.build()
                    val still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).auto().apply {
                        addTarget(jpegSurface)
                        set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(sensor, facing, displayRotation))
                        set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    }.build()
                    try {
                        s.setRepeatingRequest(settle, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                if (shooting || closed) return
                                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                                val af = if (autofocus) result.get(CaptureResult.CONTROL_AF_STATE) else null
                                if (!shotReady(ae, af, SystemClock.elapsedRealtime() - began)) return
                                shooting = true
                                runCatching {
                                    s.stopRepeating()
                                    s.capture(still, null, handler)
                                }.onFailure { fail("the camera couldn't take the photo: ${it.message}") }
                            }
                        }, handler)
                    } catch (e: CameraAccessException) {
                        fail("the camera stopped: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    session = s
                    fail("the camera couldn't be set up for a photo")
                }
            }, handler)
        }

        private fun saved(reader: ImageReader) {
            val image = reader.acquireLatestImage() ?: return
            val bytes = image.use {
                val buffer = it.planes[0].buffer
                ByteArray(buffer.remaining()).also { b -> buffer.get(b) }
            }
            if (closed) return
            val written = runCatching { file.writeBytes(bytes) }
            close()
            written.onSuccess { report.taken(file) }.onFailure {
                file.delete()
                report.fail("couldn't write the photo: ${it.message}")
            }
        }

        // Also closes a camera that opened or failed after the shot was cancelled.
        private fun fail(message: String) {
            val already = closed
            close()
            if (already) return
            file.delete()
            report.fail(message)
        }

        fun close() {
            if (closed && device == null) return
            closed = true
            handler.removeCallbacks(timeout)
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { preview?.close() }
            runCatching { jpeg?.close() }
            session = null
            device = null
        }
    }
}
