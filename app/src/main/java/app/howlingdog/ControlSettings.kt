package app.howlingdog

/**
 * The on-screen controls' own settings (haptics, opacity, size,
 * layout), stored in the same SharedPreferences file as the core
 * options but under their own "controls.*" keys. Values are
 * validated on read, so a bad or missing value always falls back
 * to the default instead of breaking the overlay.
 *
 * Positions are the CENTRE of a control as a fraction of the game
 * area (0..1 in each axis), so a layout survives rotation and
 * different screen sizes. The game area is always landscape while
 * playing, so one pair of fractions per control is enough.
 */
data class ControlPosition(val x: Float, val y: Float)

/** The three control sizes, as a scale applied to the stick and the buttons together. */
enum class ControlSize(val scale: Float) {
    SMALL(0.85f),
    MEDIUM(1.0f),
    LARGE(1.18f),
}

class ControlSettings(private val store: OptionStore) {

    companion object {
        const val KEY_HAPTICS = "controls.haptics"
        const val KEY_OPACITY = "controls.opacity" // int percent, 20..100
        const val KEY_SIZE = "controls.size"
        const val KEY_STICK_POS = "controls.stickPos" // "x,y" fractions
        const val KEY_BUTTONS_POS = "controls.buttonsPos"

        val DEFAULT_HAPTICS = HapticLevel.LIGHT
        const val DEFAULT_OPACITY = 60 // percent — matches the old fixed 0.6 alpha
        val DEFAULT_SIZE = ControlSize.MEDIUM
        val DEFAULT_STICK_POS = ControlPosition(0.14f, 0.72f)
        val DEFAULT_BUTTONS_POS = ControlPosition(0.86f, 0.72f)

        const val MIN_OPACITY = 20
        const val MAX_OPACITY = 100
    }

    fun haptics(): HapticLevel {
        return when (store.get(KEY_HAPTICS)?.lowercase()) {
            "off" -> HapticLevel.OFF
            "light" -> HapticLevel.LIGHT
            "medium" -> HapticLevel.MEDIUM
            "heavy" -> HapticLevel.HEAVY
            else -> DEFAULT_HAPTICS
        }
    }

    fun setHaptics(level: HapticLevel) {
        store.put(KEY_HAPTICS, level.name.lowercase())
    }

    /** Opacity as 0.0..1.0 (the slider stores an int percent). */
    fun opacity(): Float {
        val pct = store.get(KEY_OPACITY)?.toIntOrNull() ?: DEFAULT_OPACITY
        return pct.coerceIn(MIN_OPACITY, MAX_OPACITY) / 100f
    }

    fun setOpacity(percent: Int) {
        store.put(KEY_OPACITY, percent.coerceIn(MIN_OPACITY, MAX_OPACITY).toString())
    }

    fun size(): ControlSize {
        return when (store.get(KEY_SIZE)?.lowercase()) {
            "small" -> ControlSize.SMALL
            "medium" -> ControlSize.MEDIUM
            "large" -> ControlSize.LARGE
            else -> DEFAULT_SIZE
        }
    }

    fun setSize(size: ControlSize) {
        store.put(KEY_SIZE, size.name.lowercase())
    }

    fun stickPosition(): ControlPosition = readPosition(KEY_STICK_POS, DEFAULT_STICK_POS)

    fun buttonsPosition(): ControlPosition = readPosition(KEY_BUTTONS_POS, DEFAULT_BUTTONS_POS)

    fun setStickPosition(pos: ControlPosition) {
        store.put(KEY_STICK_POS, writePosition(pos))
    }

    fun setButtonsPosition(pos: ControlPosition) {
        store.put(KEY_BUTTONS_POS, writePosition(pos))
    }

    /** Restore both control positions to their defaults. */
    fun resetLayout() {
        setStickPosition(DEFAULT_STICK_POS)
        setButtonsPosition(DEFAULT_BUTTONS_POS)
    }

    private fun readPosition(key: String, default: ControlPosition): ControlPosition {
        val raw = store.get(key) ?: return default
        val parts = raw.split(",")
        if (parts.size != 2) return default
        val x = parts[0].toFloatOrNull() ?: return default
        val y = parts[1].toFloatOrNull() ?: return default
        if (x !in 0.02f..0.98f || y !in 0.02f..0.98f) return default
        return ControlPosition(x, y)
    }

    private fun writePosition(pos: ControlPosition): String {
        val x = pos.x.coerceIn(0.02f, 0.98f)
        val y = pos.y.coerceIn(0.02f, 0.98f)
        return "$x,$y"
    }
}
