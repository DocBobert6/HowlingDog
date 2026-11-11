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
 *
 * 2.0, feature 5 — how the click actually feels depends on the
 * phone's haptic hardware, and the code is honest about it: a
 * Pixel's linear resonant actuator renders the tick, the click
 * and the Coin thud crisply, each distinct; a cheap eccentric
 * rotating mass motor renders all three as a generic buzz. The
 * code asks for the same effects either way — the motor is the
 * limit, not the app. Nothing here claims a vibration is a
 * metallic sound.
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

    /**
     * 2.0, feature 5 — the Coin button's thud: a longer,
     * full-amplitude one-shot (60ms at 255), heavier than any
     * level's click, so the coin drop feels distinct from a
     * button press at every level. It is a heavier buzz, not a
     * true metallic thunk — no phone motor can make a metal
     * sound, and the code does not claim it does. Used on every
     * API level (there is no predefined "thud" effect).
     */
    const val COIN_THUD_DURATION_MS = 60
    const val COIN_THUD_AMPLITUDE = 255
}

/**
 * 2.0, feature 5 — the microswitch actuation detector: when the
 * on-screen stick (or D-pad) crosses an actuation threshold.
 * Pure (unit-tested on the JVM); the stick and the D-pad both
 * drive their click through it.
 *
 * A click fires when the actuated direction set changes and the
 * new set is not empty: neutral -> a direction clicks, and one
 * direction -> another clicks (UP to UP+RIGHT clicks). A release
 * back to neutral does not click (the switch returning is
 * silent, as before). Button bits are ignored — only the
 * direction set matters, so a button press while holding a
 * direction never re-clicks the stick.
 */
object ActuationDetector {

    /** The direction bits of a joypad mask (up/down/left/right). */
    val DIRECTION_BITS: Int =
        (1 shl InputMask.JOY_UP) or (1 shl InputMask.JOY_DOWN) or
            (1 shl InputMask.JOY_LEFT) or (1 shl InputMask.JOY_RIGHT)

    /** True when the stick crossed an actuation threshold between these two masks. */
    fun isActuation(previousMask: Int, newMask: Int): Boolean {
        val previous = previousMask and DIRECTION_BITS
        val current = newMask and DIRECTION_BITS
        return current != 0 && current != previous
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

    /**
     * 2.0, feature 5 — the Coin button's thud (see
     * [HapticEffect.COIN_THUD_DURATION_MS]): a longer,
     * full-amplitude buzz, distinct from a button press at every
     * level. Respects OFF like every haptic — no vibration when
     * haptics are off.
     */
    fun performCoin(level: HapticLevel) {
        val v = vibrator ?: return
        if (level == HapticLevel.OFF) return
        v.vibrate(
            VibrationEffect.createOneShot(
                HapticEffect.COIN_THUD_DURATION_MS.toLong(),
                HapticEffect.COIN_THUD_AMPLITUDE,
            ),
        )
    }
}
