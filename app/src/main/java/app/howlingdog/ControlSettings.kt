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
 *
 * Each control group also has its own scale multiplier, set by
 * dragging its resize handle in adjust mode. The effective size
 * is the Settings preset (Small/Medium/Large) times the group's
 * own scale, so changing the preset rescales both groups
 * proportionally and never clobbers a per-group adjustment.
 */
data class ControlPosition(val x: Float, val y: Float)

/** The three control sizes, as a scale applied to the stick and the buttons together. */
enum class ControlSize(val scale: Float) {
    SMALL(0.85f),
    MEDIUM(1.0f),
    LARGE(1.18f),
}

/** The on-screen left control: the arcade stick, or the 8-way D-pad. */
enum class LeftControl {
    STICK,
    DPAD,
}

class ControlSettings(private val store: OptionStore) {

    companion object {
        const val KEY_HAPTICS = "controls.haptics"
        const val KEY_OPACITY = "controls.opacity" // int percent, 20..100 (legacy: one opacity for the whole overlay)
        /** Per-group opacity (int percent, 20..100): the stick/D-pad
            and the buttons each have their own. Absent means the
            legacy KEY_OPACITY value, so a user who already set an
            opacity keeps it for both groups. */
        const val KEY_STICK_OPACITY = "controls.stickOpacity"
        const val KEY_BUTTONS_OPACITY = "controls.buttonsOpacity"
        const val KEY_SIZE = "controls.size"
        const val KEY_STICK_POS = "controls.stickPos" // "x,y" fractions
        const val KEY_BUTTONS_POS = "controls.buttonsPos"
        const val KEY_LEFT_CONTROL = "controls.leftControl" // "stick" / "dpad"
        const val KEY_STICK_SCALE = "controls.stickScale" // float, 0.7..1.5
        const val KEY_BUTTONS_SCALE = "controls.buttonsScale"
        /** The D-pad's own resize multiplier (float, 0.7..1.5). The
            stick and the D-pad share one slot but are sized
            separately; absent means the stick's scale, so switching
            to the D-pad keeps the size the stick had. */
        const val KEY_DPAD_SCALE = "controls.dpadScale"
        /** Per-button size multipliers, "controls.buttonSize.<id>" =
            float, 0.7..1.5 (1.0 = the group size). One key per
            button id; absent means 1.0. */
        const val KEY_BUTTON_SIZE_PREFIX = "controls.buttonSize."
        /** Per-button offset overrides, "dx,dy" in dp at scale 1.0
            (relative to the cluster centre). One key per button id
            ("A", "B", "C", "D", "Coin", "Start"); absent means the
            theme layout's default offset for that button. */
        const val KEY_BUTTON_OFFSET_PREFIX = "controls.buttonOffset."
        const val MAX_BUTTON_OFFSET_DP = 500f
        /** Per-button turbo (autofire) toggles, "controls.turbo.<id>" = "on"/"off", default off. */
        const val KEY_TURBO_PREFIX = "controls.turbo."
        /** Gamepad button remapping (v1.3.0, part 1):
            "controls.gamepad.<neoId>" = the Android keycode NAME
            ("BUTTON_A", "BUTTON_X", "BUTTON_START", ...). Absent
            means the default for that Neo input. */
        const val KEY_GAMEPAD_PREFIX = "controls.gamepad."
        val TURBO_BUTTON_IDS: List<String> = listOf("A", "B", "C", "D")

        val BUTTON_IDS: List<String> = listOf("A", "B", "C", "D", "Coin", "Start")

        val DEFAULT_HAPTICS = HapticLevel.LIGHT
        const val DEFAULT_OPACITY = 60 // percent — matches the old fixed 0.6 alpha
        val DEFAULT_SIZE = ControlSize.MEDIUM
        val DEFAULT_LEFT_CONTROL = LeftControl.STICK
        val DEFAULT_STICK_POS = ControlPosition(0.14f, 0.72f)
        val DEFAULT_BUTTONS_POS = ControlPosition(0.86f, 0.72f)
        const val DEFAULT_GROUP_SCALE = 1.0f

        const val MIN_OPACITY = 20
        const val MAX_OPACITY = 100
        const val MIN_GROUP_SCALE = 0.7f
        const val MAX_GROUP_SCALE = 1.5f
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

    /** The stick/D-pad's own opacity (0.0..1.0). Falls back to the
        legacy whole-overlay opacity when never set, so an existing
        user's choice carries over. */
    fun stickOpacity(): Float = readOpacity(KEY_STICK_OPACITY)

    /** The buttons' own opacity (0.0..1.0). Same fallback as the stick's. */
    fun buttonsOpacity(): Float = readOpacity(KEY_BUTTONS_OPACITY)

    fun setStickOpacity(percent: Int) {
        store.put(KEY_STICK_OPACITY, percent.coerceIn(MIN_OPACITY, MAX_OPACITY).toString())
    }

    fun setButtonsOpacity(percent: Int) {
        store.put(KEY_BUTTONS_OPACITY, percent.coerceIn(MIN_OPACITY, MAX_OPACITY).toString())
    }

    private fun readOpacity(key: String): Float {
        val pct = store.get(key)?.toIntOrNull()
            ?: store.get(KEY_OPACITY)?.toIntOrNull()
            ?: DEFAULT_OPACITY
        return pct.coerceIn(MIN_OPACITY, MAX_OPACITY) / 100f
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

    /** The on-screen left control (default the stick). */
    fun leftControl(): LeftControl {
        return when (store.get(KEY_LEFT_CONTROL)?.lowercase()) {
            "dpad", "d-pad" -> LeftControl.DPAD
            "stick" -> LeftControl.STICK
            else -> DEFAULT_LEFT_CONTROL
        }
    }

    fun setLeftControl(control: LeftControl) {
        store.put(KEY_LEFT_CONTROL, control.name.lowercase())
    }

    /** True when this button's turbo toggle is on (default off; unknown ids are never turbo). */
    fun turboEnabled(id: String): Boolean =
        id in TURBO_BUTTON_IDS && store.get(KEY_TURBO_PREFIX + id) == "on"

    fun setTurboEnabled(id: String, on: Boolean) {
        if (id !in TURBO_BUTTON_IDS) return
        store.put(KEY_TURBO_PREFIX + id, if (on) "on" else "off")
    }

    /** The joypad mask of the buttons with turbo on (0 when none). */
    fun turboMask(): Int {
        var mask = 0
        for (id in TURBO_BUTTON_IDS) {
            if (turboEnabled(id)) mask = mask or TurboInput.bitFor(id)
        }
        return mask
    }

    /** The gamepad button mapping (Neo id -> Android keycode). Every
        Neo input is present, defaulting to today's mapping for any
        input never remapped. */
    fun gamepadMapping(): Map<String, Int> =
        GamepadMapping.NEO_IDS.associateWith { gamepadKeycode(it) }

    /** The physical keycode assigned to a Neo input, or its default. */
    fun gamepadKeycode(neoId: String): Int {
        val stored = GamepadMapping.keyCodeForName(store.get(KEY_GAMEPAD_PREFIX + neoId))
        return stored ?: GamepadMapping.DEFAULT[neoId] ?: 0
    }

    /** Store one Neo input's physical button. */
    fun setGamepadKeycode(neoId: String, keyCode: Int) {
        if (neoId !in GamepadMapping.NEO_IDS) return
        if (!GamepadMapping.isRemappable(keyCode)) return
        store.put(KEY_GAMEPAD_PREFIX + neoId, GamepadMapping.keyCodeName(keyCode))
    }

    /** Store a whole mapping (an assign swaps two entries, so both are written). */
    fun setGamepadMapping(mapping: Map<String, Int>) {
        for (neoId in GamepadMapping.NEO_IDS) {
            setGamepadKeycode(neoId, GamepadMapping.keyCodeFor(neoId, mapping))
        }
    }

    /** Drop every remapping (all six inputs return to the defaults). */
    fun resetGamepadMapping() {
        for (neoId in GamepadMapping.NEO_IDS) store.put(KEY_GAMEPAD_PREFIX + neoId, "")
    }

    fun stickPosition(): ControlPosition = readPosition(KEY_STICK_POS, DEFAULT_STICK_POS)

    fun buttonsPosition(): ControlPosition = readPosition(KEY_BUTTONS_POS, DEFAULT_BUTTONS_POS)

    fun setStickPosition(pos: ControlPosition) {
        store.put(KEY_STICK_POS, writePosition(pos))
    }

    fun setButtonsPosition(pos: ControlPosition) {
        store.put(KEY_BUTTONS_POS, writePosition(pos))
    }

    /** Per-group resize multiplier (1.0 = the preset size). */
    fun stickScale(): Float = readScale(KEY_STICK_SCALE)

    fun buttonsScale(): Float = readScale(KEY_BUTTONS_SCALE)

    fun setStickScale(scale: Float) {
        store.put(KEY_STICK_SCALE, scale.coerceIn(MIN_GROUP_SCALE, MAX_GROUP_SCALE).toString())
    }

    fun setButtonsScale(scale: Float) {
        store.put(KEY_BUTTONS_SCALE, scale.coerceIn(MIN_GROUP_SCALE, MAX_GROUP_SCALE).toString())
    }

    /** The D-pad's own resize multiplier (1.0 = the preset size).
        Falls back to the stick's scale when never set, so switching
        to the D-pad keeps the size the stick had. */
    fun dpadScale(): Float {
        val raw = store.get(KEY_DPAD_SCALE)?.toFloatOrNull()
        return if (raw != null && raw in MIN_GROUP_SCALE..MAX_GROUP_SCALE) raw else stickScale()
    }

    fun setDpadScale(scale: Float) {
        store.put(KEY_DPAD_SCALE, scale.coerceIn(MIN_GROUP_SCALE, MAX_GROUP_SCALE).toString())
    }

    /** One button's own size multiplier (1.0 = the group size), or
        1.0 when never set. */
    fun buttonSizeMult(id: String): Float = readScale(KEY_BUTTON_SIZE_PREFIX + id)

    /** All stored per-button size multipliers, by button id. */
    fun buttonSizeMults(): Map<String, Float> =
        BUTTON_IDS.mapNotNull { id ->
            store.get(KEY_BUTTON_SIZE_PREFIX + id)?.toFloatOrNull()
                ?.takeIf { it in MIN_GROUP_SCALE..MAX_GROUP_SCALE }
                ?.let { id to it }
        }.toMap()

    fun setButtonSizeMult(id: String, mult: Float) {
        if (id !in BUTTON_IDS) return
        store.put(
            KEY_BUTTON_SIZE_PREFIX + id,
            mult.coerceIn(MIN_GROUP_SCALE, MAX_GROUP_SCALE).toString(),
        )
    }

    /** Drop every per-button size multiplier (the buttons return to the group size). */
    fun clearButtonSizeMults() {
        for (id in BUTTON_IDS) store.put(KEY_BUTTON_SIZE_PREFIX + id, "")
    }

    /** Restore both control positions, all three group scales, and the per-button sizes to their defaults. */
    fun resetLayout() {
        setStickPosition(DEFAULT_STICK_POS)
        setButtonsPosition(DEFAULT_BUTTONS_POS)
        setStickScale(DEFAULT_GROUP_SCALE)
        setButtonsScale(DEFAULT_GROUP_SCALE)
        setDpadScale(DEFAULT_GROUP_SCALE)
        clearButtonOffsets()
        clearButtonSizeMults()
    }

    /** A button's stored offset override (dp at scale 1.0, relative to the cluster centre), or null when it uses the theme layout's default. */
    fun buttonOffset(id: String): Pair<Float, Float>? {
        val raw = store.get(KEY_BUTTON_OFFSET_PREFIX + id) ?: return null
        if (raw.isBlank()) return null
        val parts = raw.split(",")
        if (parts.size != 2) return null
        val dx = parts[0].toFloatOrNull() ?: return null
        val dy = parts[1].toFloatOrNull() ?: return null
        if (dx !in -MAX_BUTTON_OFFSET_DP..MAX_BUTTON_OFFSET_DP ||
            dy !in -MAX_BUTTON_OFFSET_DP..MAX_BUTTON_OFFSET_DP
        ) return null
        return dx to dy
    }

    /** All stored button offset overrides, by button id. */
    fun buttonOffsets(): Map<String, Pair<Float, Float>> =
        BUTTON_IDS.mapNotNull { id -> buttonOffset(id)?.let { id to it } }.toMap()

    /** Store one button's offset override (dp at scale 1.0). */
    fun setButtonOffset(id: String, offset: Pair<Float, Float>) {
        val (dx, dy) = offset
        store.put(
            KEY_BUTTON_OFFSET_PREFIX + id,
            "${dx.coerceIn(-MAX_BUTTON_OFFSET_DP, MAX_BUTTON_OFFSET_DP)}," +
                dy.coerceIn(-MAX_BUTTON_OFFSET_DP, MAX_BUTTON_OFFSET_DP),
        )
    }

    /** Drop every button offset override (the buttons return to the theme layout's defaults). */
    fun clearButtonOffsets() {
        for (id in BUTTON_IDS) store.put(KEY_BUTTON_OFFSET_PREFIX + id, "")
    }

    private fun readScale(key: String): Float {
        val raw = store.get(key)?.toFloatOrNull() ?: return DEFAULT_GROUP_SCALE
        return if (raw in MIN_GROUP_SCALE..MAX_GROUP_SCALE) raw else DEFAULT_GROUP_SCALE
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
