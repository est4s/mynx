package io.github.est4s.terminal.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VibrationRequestTest {
    private val base = createTempDirectory("vibration").toFile()
    private val dir = File(base, "requests").apply { mkdirs() }
    private val home = File(base, "root").apply { mkdirs() }

    // A Pixel on Android 16: every effect, most primitives.
    private var info: VibratorInfo? = VibratorInfo(
        sdk = 36,
        amplitudeControl = true,
        effects = HapticEffect.entries.associateWith { true },
        primitives = mapOf(
            HapticPrimitive.CLICK to 12, HapticPrimitive.TICK to 5, HapticPrimitive.LOW_TICK to 8,
            HapticPrimitive.THUD to 300, HapticPrimitive.QUICK_RISE to 150,
            HapticPrimitive.SLOW_RISE to 500, HapticPrimitive.QUICK_FALL to 100,
        ),
    )
    private val played = mutableListOf<Vibration>()
    private var refusal: String? = null
    private var stops = 0

    private class Timer(val ms: Long, val block: () -> Unit) {
        var cancelled = false
    }

    private val timers = mutableListOf<Timer>()
    private val haptics = object : Haptics {
        override fun info() = info
        override fun play(vibration: Vibration): String? {
            if (refusal == null) played += vibration
            return refusal
        }
        override fun stop() {
            stops++
        }
    }
    private val vibrations = Vibrations(haptics) { ms, block ->
        val timer = Timer(ms, block).also { timers += it }
        return@Vibrations { timer.cancelled = true }
    }
    private val requests = MynxRequests(
        dir, home,
        vibrate = vibrations::oneShot,
        later = vibrations.requests(),
        alive = { true },
    )

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun `one buzz at the phone's strength, or at the strength asked`() {
        assertEquals("""{"ok":true}""", ask("vibrate"))
        assertEquals("""{"ok":true}""", ask("vibrate", "50", "strength=40"))
        assertEquals("""{"ok":true}""", ask("vibrate", "strength=100"))
        assertEquals(
            listOf<Vibration>(Vibration.OneShot(300, null), Vibration.OneShot(50, 102), Vibration.OneShot(300, 255)),
            played,
        )
    }

    @Test
    fun `strength is 1 to 100 percent, and needs amplitude control`() {
        val error = """{"ok":false,"error":"the strength is 1 to 100 %"}"""
        assertEquals(error, ask("vibrate", "50", "strength=0"))
        assertEquals(error, ask("vibrate", "50", "strength=101"))
        assertEquals(error, ask("vibrate", "50", "strength=hard"))
        info = info!!.copy(amplitudeControl = false)
        assertEquals(
            """{"ok":false,"error":"this phone's vibrator has one strength (no amplitude control)"}""",
            ask("vibrate", "50", "strength=50"),
        )
        assertTrue(played.isEmpty())
    }

    @Test
    fun `no vibrator, no buzz`() {
        info = null
        assertEquals("""{"ok":false,"error":"the phone has no vibrator"}""", ask("vibrate"))
        assertEquals("""{"ok":false,"error":"the phone has no vibrator"}""", send("1-1", "vibrate-wave", "100"))
    }

    @Test
    fun `an effect plays, and the answer comes when it's over`() {
        send("1-1", "vibrate-effect", "double-click")
        assertEquals("stream", file("1-1.wait").readText())
        assertEquals(listOf<Vibration>(Vibration.Effect(HapticEffect.DOUBLE_CLICK)), played)
        val timer = timers.single()
        assertEquals(HapticEffect.DOUBLE_CLICK.estimateMs, timer.ms)
        assertFalse(file("1-1.reply").exists())
        timer.block()
        assertEquals("""{"ok":true,"ms":${timer.ms}}""", file("1-1.reply").readText())
    }

    @Test
    fun `effects the phone lacks, or can't know, are refused`() {
        assertEquals(
            """{"ok":false,"error":"no effect 'buzz': click, double-click, tick, heavy-click"}""",
            send("1-1", "vibrate-effect", "buzz"),
        )
        info = info!!.copy(effects = info!!.effects + (HapticEffect.HEAVY_CLICK to false))
        assertEquals(
            """{"ok":false,"error":"this phone has no heavy-click; mynx vibrate list shows what it has"}""",
            send("1-2", "vibrate-effect", "heavy-click"),
        )
        info = info!!.copy(sdk = 28)
        assertEquals(
            """{"ok":false,"error":"effects need Android 10 or later"}""",
            send("1-3", "vibrate-effect", "click"),
        )
        assertTrue(played.isEmpty())
        // Unknown support (Android 10): it's tried.
        info = info!!.copy(sdk = 29, effects = HapticEffect.entries.associateWith { null })
        send("1-4", "vibrate-effect", "tick")
        assertEquals(listOf<Vibration>(Vibration.Effect(HapticEffect.TICK)), played)
    }

    @Test
    fun `a wave is steps of length and strength`() {
        send("1-1", "vibrate-wave", "200", "100@0", "300@50")
        assertEquals(
            listOf<Vibration>(Vibration.Wave(listOf(WaveStep(200, 100), WaveStep(100, 0), WaveStep(300, 50)), repeat = false)),
            played,
        )
        assertEquals(listOf(255, 0, 128), (played.single() as Vibration.Wave).steps.map { it.amplitude })
        assertEquals(600L, timers.single().ms)
    }

    @Test
    fun `a wave is at most 60 s, and something in it vibrates`() {
        assertEquals(
            """{"ok":false,"error":"a wave is at most 60 s (--repeat plays it again and again)"}""",
            send("1-1", "vibrate-wave", "30000", "30000@0", "1"),
        )
        assertEquals(
            """{"ok":false,"error":"a wave needs a step that vibrates"}""",
            send("1-2", "vibrate-wave", "100@0"),
        )
        assertEquals("""{"ok":false,"error":"a wave needs steps: MS or MS@PERCENT"}""", send("1-3", "vibrate-wave"))
        for ((n, step) in listOf("0", "-5", "x", "100@101", "100@", "@50", "100@50@2").withIndex()) {
            assertEquals(
                """{"ok":false,"error":"a wave step is MS or MS@PERCENT (1 to 60000 ms, 0 to 100 %): $step"}""",
                send("2-$n", "vibrate-wave", step),
            )
        }
        assertTrue(played.isEmpty())
    }

    @Test
    fun `without amplitude control a wave step is on or off`() {
        info = info!!.copy(amplitudeControl = false)
        assertEquals(
            """{"ok":false,"error":"this phone's vibrator has one strength (no amplitude control): steps are MS or MS@0"}""",
            send("1-1", "vibrate-wave", "100", "100@50"),
        )
        send("1-2", "vibrate-wave", "100", "100@0", "100@100")
        assertEquals(1, played.size)
    }

    @Test
    fun `a repeating wave runs until mynx stops it`() {
        send("1-1", "vibrate-wave", "100", "100@0", "repeat")
        assertEquals(listOf<Vibration>(Vibration.Wave(listOf(WaveStep(100, 100), WaveStep(100, 0)), repeat = true)), played)
        assertTrue(timers.isEmpty())
        file("1-1.cancel").writeText("")
        requests.sweep()
        assertEquals(1, stops)
        assertEquals("""{"ok":true}""", file("1-1.reply").readText())
    }

    @Test
    fun `a chain of primitives, with strength and pauses`() {
        send("1-1", "vibrate-chain", "click", "thud@50+100", "slow-rise+20")
        assertEquals(
            listOf<Vibration>(
                Vibration.Chain(
                    listOf(
                        ChainStep(HapticPrimitive.CLICK, 100, 0),
                        ChainStep(HapticPrimitive.THUD, 50, 100),
                        ChainStep(HapticPrimitive.SLOW_RISE, 100, 20),
                    ),
                ),
            ),
            played,
        )
        // The phone's durations: 12 + 100 + 300 + 20 + 500.
        assertEquals(932L, timers.single().ms)
    }

    @Test
    fun `a chain guesses durations the phone doesn't give`() {
        info = info!!.copy(sdk = 30, primitives = mapOf(HapticPrimitive.CLICK to null))
        send("1-1", "vibrate-chain", "click", "click+50")
        assertEquals(2 * HapticPrimitive.CLICK.estimateMs + 50, timers.single().ms)
    }

    @Test
    fun `primitives the phone lacks are refused`() {
        assertEquals(
            """{"ok":false,"error":"this phone has no spin; mynx vibrate list shows what it has"}""",
            send("1-1", "vibrate-chain", "click", "spin"),
        )
        assertEquals(
            """{"ok":false,"error":"no primitive 'boom': click, tick, low-tick, thud, spin, quick-rise, slow-rise, quick-fall"}""",
            send("1-2", "vibrate-chain", "boom"),
        )
        info = info!!.copy(sdk = 30)
        assertEquals(
            """{"ok":false,"error":"thud needs Android 12 or later"}""",
            send("1-3", "vibrate-chain", "thud"),
        )
        info = info!!.copy(sdk = 29)
        assertEquals(
            """{"ok":false,"error":"chains need Android 11 or later"}""",
            send("1-4", "vibrate-chain", "click"),
        )
        assertTrue(played.isEmpty())
    }

    @Test
    fun `chain steps are checked`() {
        assertEquals(
            """{"ok":false,"error":"a chain needs primitives: NAME[@PERCENT][+MS]"}""",
            send("1-1", "vibrate-chain"),
        )
        for ((n, step) in listOf("click@0", "click@101", "click+-1", "click+10001", "click@", "click+x").withIndex()) {
            assertEquals(
                """{"ok":false,"error":"a chain step is NAME[@PERCENT][+MS] (1 to 100 %, 0 to 10000 ms): $step"}""",
                send("2-$n", "vibrate-chain", step),
            )
        }
        assertEquals(
            """{"ok":false,"error":"a chain is at most 60 s"}""",
            send("3-1", "vibrate-chain", *Array(7) { "click+10000" }),
        )
        assertTrue(played.isEmpty())
    }

    @Test
    fun `what the phone can't start is said`() {
        refusal = "couldn't vibrate: bad effect"
        assertEquals("""{"ok":false,"error":"couldn't vibrate: bad effect"}""", send("1-1", "vibrate-chain", "click"))
        assertTrue(timers.isEmpty())
    }

    @Test
    fun `stop ends any vibration and its wait`() {
        send("1-1", "vibrate-wave", "100", "repeat")
        assertEquals("""{"ok":true}""", send("2-1", "vibrate-stop"))
        assertEquals(1, stops)
        assertEquals("""{"ok":true,"stopped":true}""", file("1-1.reply").readText())
        // Nothing runs: stop still makes sure.
        assertEquals("""{"ok":true}""", send("2-2", "vibrate-stop"))
        assertEquals(2, stops)
    }

    @Test
    fun `a new vibration takes over from the one before`() {
        send("1-1", "vibrate-wave", "100", "repeat")
        send("1-2", "vibrate-effect", "click")
        assertEquals("""{"ok":true,"stopped":true}""", file("1-1.reply").readText())
        // Ctrl+C on the old one mustn't stop the new one.
        file("1-1.cancel").writeText("")
        requests.sweep()
        assertEquals(0, stops)
        ask("vibrate", "20")
        assertEquals("""{"ok":true,"stopped":true}""", file("1-2.reply").readText())
        // Its timer coming due changes nothing.
        timers.single().block()
        assertEquals("""{"ok":true,"stopped":true}""", file("1-2.reply").readText())
    }

    @Test
    fun `stopping a vibration that ended leaves the next one alone`() {
        send("1-1", "vibrate-effect", "click")
        timers.single().block()
        send("1-2", "vibrate-wave", "100", "repeat")
        file("1-1.cancel").writeText("")
        requests.sweep()
        assertEquals(0, stops)
    }

    @Test
    fun `Ctrl+C stops the vibration and its timer`() {
        send("1-1", "vibrate-chain", "click")
        file("1-1.cancel").writeText("")
        requests.sweep()
        assertEquals(1, stops)
        assertTrue(timers.single().cancelled)
    }

    @Test
    fun `list says what the phone can do`() {
        assertEquals(
            """{"ok":true,"amplitude_control":true,""" +
                """"effects":{"click":true,"double-click":true,"tick":true,"heavy-click":true},""" +
                """"primitives":{"click":{"supported":true,"ms":12},"tick":{"supported":true,"ms":5},""" +
                """"low-tick":{"supported":true,"ms":8},"thud":{"supported":true,"ms":300},""" +
                """"spin":{"supported":false,"ms":null},"quick-rise":{"supported":true,"ms":150},""" +
                """"slow-rise":{"supported":true,"ms":500},"quick-fall":{"supported":true,"ms":100}}}""",
            send("1-1", "vibrate-list"),
        )
    }

    @Test
    fun `list on older Android says what it can't know`() {
        info = VibratorInfo(sdk = 29, amplitudeControl = false, effects = HapticEffect.entries.associateWith { null }, primitives = emptyMap())
        assertEquals(
            """{"ok":true,"amplitude_control":false,""" +
                """"effects":{"click":null,"double-click":null,"tick":null,"heavy-click":null},""" +
                """"primitives":{"click":{"supported":false,"ms":null},"tick":{"supported":false,"ms":null},""" +
                """"low-tick":{"supported":false,"ms":null},"thud":{"supported":false,"ms":null},""" +
                """"spin":{"supported":false,"ms":null},"quick-rise":{"supported":false,"ms":null},""" +
                """"slow-rise":{"supported":false,"ms":null},"quick-fall":{"supported":false,"ms":null}}}""",
            send("1-1", "vibrate-list"),
        )
        info = info!!.copy(sdk = 28)
        assertTrue(send("1-2", "vibrate-list").contains(""""effects":{"click":false,"double-click":false,"tick":false,"heavy-click":false}"""))
        info = null
        assertEquals("""{"ok":false,"error":"the phone has no vibrator"}""", send("1-3", "vibrate-list"))
    }

    private fun ask(vararg lines: String): String {
        File(dir, "q.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
        return File(dir, "q.reply").readText()
    }

    private fun send(id: String, vararg lines: String): String {
        File(dir, "$id.req").writeText(lines.joinToString("\n", postfix = "\n"))
        requests.processPending()
        return file("$id.reply").takeIf { it.exists() }?.readText().orEmpty()
    }

    private fun file(name: String) = File(dir, name)
}

class AmplitudeTest {
    @Test
    fun `percent becomes Android's amplitude, 1 to 255`() {
        assertEquals(0, amplitude(0))
        assertEquals(3, amplitude(1))
        assertEquals(128, amplitude(50))
        assertEquals(255, amplitude(100))
    }
}
