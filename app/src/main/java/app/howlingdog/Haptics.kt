package app.howlingdog

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** Haptic strength for the touch controls (stick + buttons). */
enum class HapticLevel { OFF, LIGHT, MEDIUM, HEAVY }

/**
 * The haptic effect for a level. The mapping is pure (unit-tested
 * on the JVM); the Vibrator call lives in [Haptics].
 *
 * On API 29+ the three levels are the platform's predefined
 * effects — EFFECT_TICK (a faint tick), EFFECT_CLICK (a clear
 * click), EFFECT_HEAVY_CLICK (a strong thud). Below API 29 there
 * are no predefined effects, so the fallback is a one-shot buzz
 * with a short duration and a low / medium / full amplitude.
 * OFF maps to nothing on both paths.
 */
object HapticEffect {

    /** The predefined VibrationEffect id for a level on API 29+, or null for OFF. */
    fun predefined(level: HapticLevel): Int? = when (level) {
        HapticLevel.OFF -> null
        HapticLevel.LIGHT -> VibrationEffect.EFFECT_TICK
        HapticLevel.MEDIUM -> VibrationEffect.EFFECT_CLICK
        HapticLevel.HEAVY -> VibrationEffect.EFFECT_HEAVY_CLICK
    }

    /** The (durationMs, amplitude) one-shot fallback for a level below API 29, or null for OFF. Amplitudes rise strictly with strength. */
    fun oneShot(level: HapticLevel): Pair<Int, Int>? = when (level) {
        HapticLevel.OFF -> null
        HapticLevel.LIGHT -> 15 to 60
        HapticLevel.MEDIUM -> 25 to 140
        HapticLevel.HEAVY -> 40 to 255
    }
}

/** Thin wrapper over the system Vibrator, so the UI just calls [perform] with the stored level. */
class Haptics(private val vibrator: Vibrator?) {

    fun perform(level: HapticLevel) {
        val v = vibrator ?: return
        if (level == HapticLevel.OFF) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = HapticEffect.predefined(level) ?: return
            v.vibrate(VibrationEffect.createPredefined(effect))
        } else {
            val (durationMs, amplitude) = HapticEffect.oneShot(level) ?: return
            @Suppress("DEPRECATION")
            v.vibrate(VibrationEffect.createOneShot(durationMs.toLong(), amplitude))
        }
    }
}
