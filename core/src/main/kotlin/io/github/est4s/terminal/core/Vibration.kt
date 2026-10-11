package io.github.est4s.terminal.core

/** The longest wave (one round of it) or chain `mynx vibrate` plays. */
const val MAX_VIBRATION_MS = 60_000L
const val MAX_WAVE_STEPS = 1000
const val MAX_CHAIN_STEPS = 100
const val MAX_CHAIN_DELAY_MS = 10_000
const val MAX_VIBRATION_PERCENT = 100

/**
 * Android's predefined effects (Android 10+). Android doesn't say how
 * long one lasts, so [estimateMs], a little over what phones play, is
 * how long `mynx` waits.
 */
enum class HapticEffect(val word: String, val estimateMs: Long) {
    CLICK("click", 30),
    DOUBLE_CLICK("double-click", 150),
    TICK("tick", 20),
    HEAVY_CLICK("heavy-click", 50),
}

/**
 * Android's composition primitives, for chains, from [minSdk] on.
 * [estimateMs] stands in for the length where Android doesn't give it
 * (before Android 12).
 */
enum class HapticPrimitive(val word: String, val minSdk: Int, val estimateMs: Long) {
    CLICK("click", 30, 20),
    TICK("tick", 30, 10),
    LOW_TICK("low-tick", 31, 10),
    THUD("thud", 31, 300),
    SPIN("spin", 31, 150),
    QUICK_RISE("quick-rise", 30, 150),
    SLOW_RISE("slow-rise", 30, 500),
    QUICK_FALL("quick-fall", 30, 100),
}

/** One step of a wave: [ms] long at [percent] strength (0 is a pause). */
data class WaveStep(val ms: Long, val percent: Int) {
    val amplitude: Int get() = amplitude(percent)
}

/** One primitive of a chain, at [percent] strength, after [delayMs] of quiet. */
data class ChainStep(val primitive: HapticPrimitive, val percent: Int, val delayMs: Int)

/** What the app is asked to play; [Vibrations] has checked it against [VibratorInfo]. */
sealed interface Vibration {
    /** [amplitude] 1 to 255, or null for the phone's own. */
    data class OneShot(val ms: Long, val amplitude: Int?) : Vibration
    data class Wave(val steps: List<WaveStep>, val repeat: Boolean) : Vibration
    data class Effect(val effect: HapticEffect) : Vibration
    data class Chain(val steps: List<ChainStep>) : Vibration
}

/**
 * What the phone's vibrator can do, as Android says on [sdk]: whether
 * it has [amplitudeControl], each effect's support (null: Android can't
 * say, before 11), and the primitives it has, with their length in ms
 * (null: Android can't say, before 12).
 */
data class VibratorInfo(
    val sdk: Int,
    val amplitudeControl: Boolean,
    val effects: Map<HapticEffect, Boolean?>,
    val primitives: Map<HapticPrimitive, Int?>,
)

/** The phone's vibrator, as the app drives it. */
interface Haptics {
    /** Null when the phone has no vibrator. */
    fun info(): VibratorInfo?
    /** Starts [vibration] (replacing any other): null, or why it couldn't. */
    fun play(vibration: Vibration): String?
    fun stop()
}

/** Android's amplitude (0 to 255) for a strength in percent; any strength above 0 vibrates. */
fun amplitude(percent: Int): Int =
    if (percent <= 0) 0 else ((percent * 255 + 50) / 100).coerceIn(1, 255)

private class NotPossible(message: String) : Exception(message)

/**
 * `mynx vibrate`: checks what's asked against what the phone can do,
 * then has [haptics] play it. Effects, waves and chains wait until they
 * end, timed with [after] (run a block after some ms; returns a way to
 * cancel it): one vibration plays at a time, so a new one, or `stop`,
 * ends the wait of the one before.
 */
class Vibrations(
    private val haptics: Haptics,
    private val after: (Long, () -> Unit) -> (() -> Unit),
) {
    private var current: PendingReply? = null

    /** The plain `vibrate` request: [percent] null for the phone's strength. */
    fun oneShot(ms: Long, percent: Int?): String? {
        val info = haptics.info() ?: return NO_VIBRATOR
        if (percent != null && !info.amplitudeControl) return ONE_STRENGTH
        return start(Vibration.OneShot(ms, percent?.let(::amplitude)))
    }

    /** The requests for [MynxRequests]'s `later`. */
    fun requests(): Map<String, Later> {
        fun waiting(parse: (List<String>, VibratorInfo) -> Vibration) = Later(null) { args, reply ->
            val info = haptics.info() ?: return@Later reply.refuse(NO_VIBRATOR)
            val vibration = try {
                parse(args.map { it.trim() }.filter { it.isNotEmpty() }, info)
            } catch (e: NotPossible) {
                return@Later reply.refuse(e.message!!)
            }
            play(vibration, info, reply)
        }
        val list = Later(5) { _, reply ->
            val info = haptics.info() ?: return@Later reply.refuse(NO_VIBRATOR)
            reply.ok(*listFields(info))
        }
        val stop = Later(5) { _, reply ->
            synchronized(this) {
                haptics.stop()
                endCurrent()
            }
            reply.ok()
        }
        return mapOf(
            "vibrate-effect" to waiting(::parseEffect),
            "vibrate-wave" to waiting(::parseWave),
            "vibrate-chain" to waiting(::parseChain),
            "vibrate-list" to list,
            "vibrate-stop" to stop,
        )
    }

    private fun play(vibration: Vibration, info: VibratorInfo, reply: PendingReply) {
        val ms = length(vibration, info)
        synchronized(this) {
            start(vibration)?.let { return reply.refuse(it) }
            current = reply
        }
        val cancelTimer = ms?.let { length ->
            after(length) {
                synchronized(this) { if (current === reply) current = null }
                reply.ok("ms" to length.toString())
            }
        }
        reply.onCancel {
            cancelTimer?.invoke()
            synchronized(this) {
                if (current === reply) {
                    current = null
                    haptics.stop()
                }
            }
        }
    }

    @Synchronized
    private fun start(vibration: Vibration): String? {
        haptics.play(vibration)?.let { return it }
        endCurrent()
        return null
    }

    private fun endCurrent() {
        current?.ok("stopped" to "true")
        current = null
    }
}

private const val NO_VIBRATOR = "the phone has no vibrator"
private const val ONE_STRENGTH = "this phone's vibrator has one strength (no amplitude control)"
private const val WAVE_STEP = "a wave step is MS or MS@PERCENT (1 to $MAX_VIBRATION_MS ms, 0 to $MAX_VIBRATION_PERCENT %)"
private const val CHAIN_STEP =
    "a chain step is NAME[@PERCENT][+MS] (1 to $MAX_VIBRATION_PERCENT %, 0 to $MAX_CHAIN_DELAY_MS ms)"
private const val WAVE_STEP_PATTERN = "(\\d{1,6})(?:@(\\d{1,3}))?"
private val WAVE_STEP_FORM = Regex(WAVE_STEP_PATTERN)
private val CHAIN_STEP_FORM = Regex("([a-z-]+)(?:@(\\d{1,3}))?(?:\\+(\\d{1,5}))?")

/** How long [vibration] lasts in ms; null when it repeats until stopped. */
internal fun length(vibration: Vibration, info: VibratorInfo): Long? = when (vibration) {
    is Vibration.OneShot -> vibration.ms
    is Vibration.Wave -> if (vibration.repeat) null else vibration.steps.sumOf { it.ms }
    is Vibration.Effect -> vibration.effect.estimateMs
    is Vibration.Chain -> vibration.steps.sumOf { step ->
        step.delayMs + (info.primitives[step.primitive]?.toLong() ?: step.primitive.estimateMs)
    }
}

private fun parseEffect(args: List<String>, info: VibratorInfo): Vibration {
    val word = args.singleOrNull() ?: throw NotPossible("effect needs a name: ${HapticEffect.entries.joinToString { it.word }}")
    val effect = HapticEffect.entries.firstOrNull { it.word == word }
        ?: throw NotPossible("no effect '$word': ${HapticEffect.entries.joinToString { it.word }}")
    if (info.sdk < 29) throw NotPossible("effects need Android 10 or later")
    if (info.effects[effect] == false) throw lacks(effect.word)
    return Vibration.Effect(effect)
}

private fun parseWave(args: List<String>, info: VibratorInfo): Vibration {
    val repeat = "repeat" in args
    val words = args.filter { it != "repeat" }
    if (words.isEmpty()) throw NotPossible("a wave needs steps: MS or MS@PERCENT")
    if (words.size > MAX_WAVE_STEPS) throw NotPossible("a wave has at most $MAX_WAVE_STEPS steps")
    val steps = words.map { word ->
        val match = WAVE_STEP_FORM.matchEntire(word) ?: throw NotPossible("$WAVE_STEP: $word")
        val ms = match.groupValues[1].toLong()
        val percent = match.groupValues[2].ifEmpty { "$MAX_VIBRATION_PERCENT" }.toInt()
        if (ms !in 1..MAX_VIBRATION_MS || percent > MAX_VIBRATION_PERCENT) throw NotPossible("$WAVE_STEP: $word")
        WaveStep(ms, percent)
    }
    if (steps.sumOf { it.ms } > MAX_VIBRATION_MS) {
        throw NotPossible("a wave is at most ${MAX_VIBRATION_MS / 1000} s (--repeat plays it again and again)")
    }
    if (steps.none { it.percent > 0 }) throw NotPossible("a wave needs a step that vibrates")
    if (!info.amplitudeControl && steps.any { it.percent !in setOf(0, MAX_VIBRATION_PERCENT) }) {
        throw NotPossible("$ONE_STRENGTH: steps are MS or MS@0")
    }
    return Vibration.Wave(steps, repeat)
}

private fun parseChain(args: List<String>, info: VibratorInfo): Vibration {
    if (args.isEmpty()) throw NotPossible("a chain needs primitives: NAME[@PERCENT][+MS]")
    if (args.size > MAX_CHAIN_STEPS) throw NotPossible("a chain has at most $MAX_CHAIN_STEPS primitives")
    val steps = args.map { word ->
        val match = CHAIN_STEP_FORM.matchEntire(word) ?: throw NotPossible("$CHAIN_STEP: $word")
        val (name, percentText, delayText) = match.destructured
        val primitive = HapticPrimitive.entries.firstOrNull { it.word == name }
            ?: throw NotPossible("no primitive '$name': ${HapticPrimitive.entries.joinToString { it.word }}")
        val percent = percentText.ifEmpty { "$MAX_VIBRATION_PERCENT" }.toInt()
        val delay = delayText.ifEmpty { "0" }.toInt()
        if (percent !in 1..MAX_VIBRATION_PERCENT || delay > MAX_CHAIN_DELAY_MS) throw NotPossible("$CHAIN_STEP: $word")
        ChainStep(primitive, percent, delay)
    }
    if (info.sdk < 30) throw NotPossible("chains need Android 11 or later")
    for (step in steps) {
        val primitive = step.primitive
        if (info.sdk < primitive.minSdk) throw NotPossible("${primitive.word} needs Android 12 or later")
        if (primitive !in info.primitives) throw lacks(primitive.word)
    }
    val chain = Vibration.Chain(steps)
    if (length(chain, info)!! > MAX_VIBRATION_MS) throw NotPossible("a chain is at most ${MAX_VIBRATION_MS / 1000} s")
    return chain
}

private fun lacks(name: String) = NotPossible("this phone has no $name; mynx vibrate list shows what it has")

private fun listFields(info: VibratorInfo): Array<Pair<String, String>> {
    val effects = HapticEffect.entries.map { effect ->
        effect.word to if (info.sdk < 29) "false" else info.effects[effect]?.toString() ?: "null"
    }
    val primitives = HapticPrimitive.entries.map { primitive ->
        val supported = info.sdk >= primitive.minSdk && primitive in info.primitives
        primitive.word to obj(
            "supported" to supported.toString(),
            "ms" to (info.primitives[primitive]?.takeIf { supported }?.toString() ?: "null"),
        )
    }
    return arrayOf(
        "amplitude_control" to info.amplitudeControl.toString(),
        "effects" to obj(*effects.toTypedArray()),
        "primitives" to obj(*primitives.toTypedArray()),
    )
}
