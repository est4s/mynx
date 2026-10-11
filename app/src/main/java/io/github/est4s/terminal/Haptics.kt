package io.github.est4s.terminal

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.est4s.terminal.core.HapticEffect
import io.github.est4s.terminal.core.HapticPrimitive
import io.github.est4s.terminal.core.Haptics
import io.github.est4s.terminal.core.Vibration
import io.github.est4s.terminal.core.VibratorInfo

/** The phone's vibrator, for `mynx vibrate`; core's Vibrations checks requests first. */
class PhoneHaptics(private val context: Context) : Haptics {
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
    }

    private fun present(): Vibrator? = vibrator?.takeIf { it.hasVibrator() }

    override fun info(): VibratorInfo? {
        val v = present() ?: return null
        val effects = HapticEffect.entries.associateWith { effect ->
            if (Build.VERSION.SDK_INT >= 30) {
                when (v.areEffectsSupported(effectId(effect))[0]) {
                    Vibrator.VIBRATION_EFFECT_SUPPORT_YES -> true
                    Vibrator.VIBRATION_EFFECT_SUPPORT_NO -> false
                    else -> null
                }
            } else {
                null
            }
        }
        val primitives = mutableMapOf<HapticPrimitive, Int?>()
        if (Build.VERSION.SDK_INT >= 30) {
            for (primitive in HapticPrimitive.entries) {
                if (primitive.minSdk > Build.VERSION.SDK_INT) continue
                val id = primitiveId(primitive)
                if (!v.areAllPrimitivesSupported(id)) continue
                primitives[primitive] = if (Build.VERSION.SDK_INT >= 31) {
                    v.getPrimitiveDurations(id)[0].takeIf { it > 0 }
                } else {
                    null
                }
            }
        }
        return VibratorInfo(Build.VERSION.SDK_INT, v.hasAmplitudeControl(), effects, primitives)
    }

    override fun play(vibration: Vibration): String? {
        val v = present() ?: return "the phone has no vibrator"
        return try {
            val effect = when (vibration) {
                is Vibration.OneShot ->
                    VibrationEffect.createOneShot(vibration.ms, vibration.amplitude ?: VibrationEffect.DEFAULT_AMPLITUDE)
                is Vibration.Wave -> VibrationEffect.createWaveform(
                    vibration.steps.map { it.ms }.toLongArray(),
                    vibration.steps.map { it.amplitude }.toIntArray(),
                    if (vibration.repeat) 0 else -1,
                )
                is Vibration.Effect -> {
                    if (Build.VERSION.SDK_INT < 29) return "effects need Android 10 or later"
                    VibrationEffect.createPredefined(effectId(vibration.effect))
                }
                is Vibration.Chain -> {
                    if (Build.VERSION.SDK_INT < 30) return "chains need Android 11 or later"
                    val composition = VibrationEffect.startComposition()
                    for (step in vibration.steps) {
                        composition.addPrimitive(primitiveId(step.primitive), step.percent / 100f, step.delayMs)
                    }
                    composition.compose()
                }
            }
            // As media vibration, so the phone's vibration settings apply as they do to other apps.
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            } else {
                v.vibrate(effect)
            }
            null
        } catch (e: RuntimeException) {
            "couldn't vibrate: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    override fun stop() {
        vibrator?.cancel()
    }

    private fun effectId(effect: HapticEffect): Int = when (effect) {
        HapticEffect.CLICK -> VibrationEffect.EFFECT_CLICK
        HapticEffect.DOUBLE_CLICK -> VibrationEffect.EFFECT_DOUBLE_CLICK
        HapticEffect.TICK -> VibrationEffect.EFFECT_TICK
        HapticEffect.HEAVY_CLICK -> VibrationEffect.EFFECT_HEAVY_CLICK
    }

    private fun primitiveId(primitive: HapticPrimitive): Int = when (primitive) {
        HapticPrimitive.CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
        HapticPrimitive.TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
        HapticPrimitive.LOW_TICK -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        HapticPrimitive.THUD -> VibrationEffect.Composition.PRIMITIVE_THUD
        HapticPrimitive.SPIN -> VibrationEffect.Composition.PRIMITIVE_SPIN
        HapticPrimitive.QUICK_RISE -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
        HapticPrimitive.SLOW_RISE -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
        HapticPrimitive.QUICK_FALL -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
    }
}
