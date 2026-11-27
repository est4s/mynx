package io.github.est4s.terminal

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.est4s.terminal.core.PixelRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// EGL_RECORDABLE_ANDROID: a config whose surfaces a video encoder takes.
private const val EGL_RECORDABLE_ANDROID = 0x3142

private const val VERTEX_SHADER = """
attribute vec4 aPos;
attribute vec4 aTex;
uniform mat4 uTex;
varying vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = (uTex * aTex).xy;
}
"""

private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTex;
uniform samplerExternalOES sTex;
void main() {
    gl_FragColor = texture2D(sTex, vTex);
}
"""

/**
 * Between the X server and a video encoder (the desktop's part on the
 * other phone): the X server's renderer can't make its EGL context
 * current on a MediaCodec input surface (EGL_BAD_MATCH for every config,
 * phone try of run 45863972064), but draws into an ordinary surface, as
 * spike #118's SurfaceView. So it gets [input], a SurfaceTexture's
 * surface of [width] x [height], and each frame that arrives there is
 * drawn here, on a thread and EGL context of our own with a recordable
 * config, into the encoder's [output] (grafika's pattern), into its
 * [picture] (the rest black: the other phone's bands, core's
 * desktopFit). [repeat] draws the last frame again (a key frame asked
 * for). [frames] counts what came in. [start] throws when EGL won't do it.
 */
class EncoderBridge private constructor(
    private val output: Surface,
    private val width: Int,
    private val height: Int,
) {
    private val thread = HandlerThread("mynx-desktop-bridge").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var program = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val matrix = FloatArray(16)
    private var lastTime = 0L
    private var hasFrame = false
    @Volatile private var released = false

    /** The surface the X server draws the other phone's part into. */
    lateinit var input: Surface
        private set

    /** Frames that came in from the X server since last read. */
    val frames = AtomicInteger()

    /** Where in the video the picture goes (its pixels, from the top left); null: all of it. Drawn again at once. */
    @Volatile var picture: PixelRect? = null
        set(value) {
            if (value == field) return
            field = value
            handler.post { draw(update = false) }
        }

    private val positions = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val coords = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) fail("no EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) fail("eglInitialize")
        val config = chooseConfig()
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) fail("eglCreateContext")
        surface = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) fail("eglCreateWindowSurface on the encoder's surface")
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) fail("eglMakeCurrent on the encoder's surface")
        program = makeProgram()
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        texture = names[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(texture)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({ draw(update = true) }, handler)
        surfaceTexture = st
        input = Surface(st)
    }

    private fun chooseConfig(): EGLConfig {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        for (alpha in listOf(8, 0)) {
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, alpha,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            )
            if (EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                configs[0]?.let { return it }
            }
        }
        fail("no recordable EGL config")
    }

    private fun makeProgram(): Int {
        fun shader(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, source)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) fail("a shader didn't compile: ${GLES20.glGetShaderInfoLog(s)}")
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) fail("the program didn't link: ${GLES20.glGetProgramInfoLog(p)}")
        return p
    }

    // On the bridge's thread: the newest frame (or the last again) into the encoder.
    private fun draw(update: Boolean) {
        if (released) return
        val st = surfaceTexture ?: return
        try {
            if (update) {
                st.updateTexImage()
                st.getTransformMatrix(matrix)
                hasFrame = true
                frames.incrementAndGet()
            }
            if (!hasFrame) return
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // GL counts from the bottom; the picture's rect from the top.
            picture?.let { p -> GLES20.glViewport(p.x, height - p.y - p.height, p.width, p.height) }
            GLES20.glUseProgram(program)
            val pos = GLES20.glGetAttribLocation(program, "aPos")
            val tex = GLES20.glGetAttribLocation(program, "aTex")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(tex)
            GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, matrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            // The encoder wants its frames' times to go forward.
            val now = if (update && st.timestamp > 0) st.timestamp else System.nanoTime()
            lastTime = maxOf(now, lastTime + 1)
            EGLExt.eglPresentationTimeANDROID(display, surface, lastTime)
            EGL14.eglSwapBuffers(display, surface)
        } catch (e: RuntimeException) {
            // The surfaces are going (released under us).
        }
    }

    /** Draws the last frame again: the encoder gets a frame, and so a key frame when one is asked for. */
    fun repeat() {
        handler.post { draw(update = false) }
    }

    /** Lets go of everything; the X server must have let go of [input] first. Waits up to a second. */
    fun release() {
        val done = CountDownLatch(1)
        handler.post {
            released = true
            runCatching { surfaceTexture?.release() }
            surfaceTexture = null
            if (::input.isInitialized) runCatching { input.release() }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                // Not eglTerminate: the display is the whole app's (WebViews draw with it too).
                EGL14.eglReleaseThread()
            }
            done.countDown()
        }
        runCatching { done.await(1, TimeUnit.SECONDS) }
        thread.quitSafely()
    }

    companion object {
        /** A bridge into [output] (the encoder's input surface) of [width] x [height], set up on its own thread. */
        fun start(output: Surface, width: Int, height: Int): EncoderBridge {
            val bridge = EncoderBridge(output, width, height)
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            bridge.handler.post {
                try {
                    bridge.setUp()
                } catch (e: Throwable) {
                    failure = e
                }
                done.countDown()
            }
            if (!done.await(3, TimeUnit.SECONDS)) failure = IllegalStateException("EGL took too long to set up")
            failure?.let {
                bridge.release()
                throw it
            }
            return bridge
        }

        private fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }

        private fun fail(what: String): Nothing = throw IllegalStateException("$what (EGL error 0x${Integer.toHexString(EGL14.eglGetError())})")
    }
}
