package app.howlingdog

/**
 * Phase 9, item 8 — per-game configuration profiles.
 *
 * A game's own overrides for the settings that make sense to vary
 * per game, stored in the same SharedPreferences file
 * ("howlingdog-settings") as the global settings, under
 * "pergame.<mainFile>.<setting>" keys. The main file name is
 * sanitized (letters, digits, '.', '_', '-' only — see [sanitize]),
 * so it is safe inside a key.
 *
 * An override is either set or unset — there is no per-game copy of
 * the defaults. An unset value falls back to the global setting
 * (see the [resolve*] helpers), and [clear] drops every override
 * for a game, so it follows the global settings again. Invalid
 * values and unknown keys are rejected, never stored; a stored
 * value that fails validation reads back as unset.
 *
 * Per-game settings (the system mode is NOT here — it already lives
 * per game on the entry, see GameLibrary):
 * - Core options, the safe subset: region (the one restart option
 *   that is genuinely per-game — a Japanese cart wants Japan), and
 *   the live options palette, aspect ratio, sprites per line,
 *   overclocking, and free play. The rest stay global on purpose:
 *   the overscan crops are display geometry, Universe BIOS hardware
 *   / setting mode / four-player mode are hardware-level, the
 *   memory-card options cover the one shared card, and the CD
 *   options (speed hack, DMA limit, skip loading, ADPCM wrap) are
 *   disc-drive behaviour, not per-title tuning.
 * - CRT shader on/off and screen curvature on/off: the per-game
 *   override wins over ShaderSettings' per-system → master chain.
 * - Left control (joystick / D-pad): the per-game override wins
 *   over ControlSettings' global choice.
 * - Control layout (2.0, feature 2): the stick and button-cluster
 *   positions, the group scales, and the per-button size/offset
 *   overrides — saved for this game only when the user chooses
 *   "For this game" in the adjust-layout mode. The global layout
 *   stays the default for games without a saved override, and
 *   Reset in adjust mode clears the game's saved layout so it
 *   follows the global one again.
 *
 * The overrides are applied at game start: [applyCoreOverrides]
 * pushes the stored core values into the bridge on top of the
 * global values CoreSettings.applyAll already pushed, and the
 * display / left-control overrides are resolved where the game
 * screen reads those settings (MainActivity).
 */
class PerGameSettings(private val store: OptionStore) {

    companion object {
        const val PREFIX = "pergame."

        /**
         * The core options that may be overridden per game. Every
         * other core option stays global (see the class doc for why).
         */
        val CORE_KEYS: List<String> = listOf(
            "geolith_region",
            "geolith_palette",
            "geolith_aspect",
            "geolith_sprlimit",
            "geolith_oc",
            "geolith_freeplay",
        )

        const val KEY_SHADER = "display.shader"
        const val KEY_CURVATURE = "display.curvature"
        const val KEY_LEFT_CONTROL = "controls.leftControl"
        const val KEY_RUNAHEAD = "gameplay.runahead"

        /**
         * 2.0, feature 2 — the per-game control-layout overrides.
         * Stored in the same string formats as ControlSettings
         * ("x,y" positions, plain floats for scales, "dx,dy"
         * offsets), so the two stores stay interchangeable.
         */
        const val KEY_CONTROLS_STICK_POS = "controls.stickPos"
        const val KEY_CONTROLS_BUTTONS_POS = "controls.buttonsPos"
        const val KEY_CONTROLS_STICK_SCALE = "controls.stickScale"
        const val KEY_CONTROLS_BUTTONS_SCALE = "controls.buttonsScale"
        const val KEY_CONTROLS_DPAD_SCALE = "controls.dpadScale"
        const val KEY_CONTROLS_BUTTON_SIZE_PREFIX = "controls.buttonSize."
        const val KEY_CONTROLS_BUTTON_OFFSET_PREFIX = "controls.buttonOffset."

        /** The fixed control-layout suffixes (the per-button keys are per id, see [clearControlLayout]). */
        val CONTROL_LAYOUT_SUFFIXES: List<String> = listOf(
            KEY_CONTROLS_STICK_POS,
            KEY_CONTROLS_BUTTONS_POS,
            KEY_CONTROLS_STICK_SCALE,
            KEY_CONTROLS_BUTTONS_SCALE,
            KEY_CONTROLS_DPAD_SCALE,
        )

        /** Every suffix a profile may store (core keys + display + control + gameplay + control layout). */
        val ALL_SUFFIXES: List<String> = CORE_KEYS + listOf(
            KEY_SHADER,
            KEY_CURVATURE,
            KEY_LEFT_CONTROL,
            KEY_RUNAHEAD,
        ) + CONTROL_LAYOUT_SUFFIXES
    }

    private fun key(mainFile: String, suffix: String) = "$PREFIX$mainFile.$suffix"

    /** A stored "x,y" position, or null when unset, malformed, or out of the 0.02..0.98 bounds. */
    private fun readPosition(mainFile: String, suffix: String): ControlPosition? {
        val raw = store.get(key(mainFile, suffix)) ?: return null
        val parts = raw.split(",")
        if (parts.size != 2) return null
        val x = parts[0].toFloatOrNull() ?: return null
        val y = parts[1].toFloatOrNull() ?: return null
        if (x !in 0.02f..0.98f || y !in 0.02f..0.98f) return null
        return ControlPosition(x, y)
    }

    /** A stored scale, or null when unset, malformed, or outside 0.7..1.5. */
    private fun readScale(mainFile: String, suffix: String): Float? {
        val raw = store.get(key(mainFile, suffix))?.takeIf { it.isNotBlank() } ?: return null
        val value = raw.toFloatOrNull() ?: return null
        return value.takeIf { it in ControlSettings.MIN_GROUP_SCALE..ControlSettings.MAX_GROUP_SCALE }
    }

    /** A stored "dx,dy" offset (dp), or null when unset, malformed, or outside ±500dp. */
    private fun readOffset(mainFile: String, suffix: String): Pair<Float, Float>? {
        val raw = store.get(key(mainFile, suffix)) ?: return null
        val parts = raw.split(",")
        if (parts.size != 2) return null
        val dx = parts[0].toFloatOrNull() ?: return null
        val dy = parts[1].toFloatOrNull() ?: return null
        if (dx !in -ControlSettings.MAX_BUTTON_OFFSET_DP..ControlSettings.MAX_BUTTON_OFFSET_DP ||
            dy !in -ControlSettings.MAX_BUTTON_OFFSET_DP..ControlSettings.MAX_BUTTON_OFFSET_DP
        ) return null
        return dx to dy
    }

    private fun writePosition(pos: ControlPosition): String {
        val x = pos.x.coerceIn(0.02f, 0.98f)
        val y = pos.y.coerceIn(0.02f, 0.98f)
        return "$x,$y"
    }

    /** The stored override for a per-game core option, or null when unset or invalid. */
    fun coreOverride(mainFile: String, key: String): String? {
        if (key !in CORE_KEYS) return null
        val opt = CoreOptions.byKey(key) ?: return null
        val raw = store.get(key(mainFile, key))?.takeIf { it.isNotBlank() } ?: return null
        return raw.takeIf { value -> opt.values.any { it.first == value } }
    }

    /** Store (or clear, with null) a per-game core override. Unknown keys and invalid values are ignored. */
    fun setCoreOverride(mainFile: String, key: String, value: String?) {
        if (key !in CORE_KEYS) return
        if (value == null) {
            store.put(key(mainFile, key), "")
            return
        }
        val opt = CoreOptions.byKey(key) ?: return
        if (opt.values.none { it.first == value }) return
        store.put(key(mainFile, key), value)
    }

    /** The stored CRT-shader override for this game, or null when unset or invalid. */
    fun shaderOverride(mainFile: String): Boolean? =
        when (store.get(key(mainFile, KEY_SHADER))?.lowercase()) {
            "on" -> true
            "off" -> false
            else -> null
        }

    /** Store (or clear, with null) this game's CRT-shader override. */
    fun setShaderOverride(mainFile: String, on: Boolean?) {
        store.put(key(mainFile, KEY_SHADER), when (on) {
            true -> "on"
            false -> "off"
            null -> ""
        })
    }

    /** The stored curvature override for this game, or null when unset or invalid. */
    fun curvatureOverride(mainFile: String): Boolean? =
        when (store.get(key(mainFile, KEY_CURVATURE))?.lowercase()) {
            "on" -> true
            "off" -> false
            else -> null
        }

    /** Store (or clear, with null) this game's curvature override. */
    fun setCurvatureOverride(mainFile: String, on: Boolean?) {
        store.put(key(mainFile, KEY_CURVATURE), when (on) {
            true -> "on"
            false -> "off"
            null -> ""
        })
    }

    /** The stored left-control override for this game, or null when unset or invalid. */
    fun leftControlOverride(mainFile: String): LeftControl? =
        when (store.get(key(mainFile, KEY_LEFT_CONTROL))?.lowercase()) {
            "stick" -> LeftControl.STICK
            "dpad", "d-pad" -> LeftControl.DPAD
            else -> null
        }

    /** Store (or clear, with null) this game's left-control override. */
    fun setLeftControlOverride(mainFile: String, control: LeftControl?) {
        store.put(key(mainFile, KEY_LEFT_CONTROL), control?.name?.lowercase() ?: "")
    }

    /**
     * Phase 10 — the stored run-ahead override for this game (0/1/2
     * frames), or null when unset or invalid (an invalid stored
     * value reads as unset, so it can never turn run-ahead on by
     * accident).
     */
    fun runaheadOverride(mainFile: String): Int? {
        val raw = store.get(key(mainFile, KEY_RUNAHEAD))?.takeIf { it.isNotBlank() } ?: return null
        return when (raw.lowercase()) {
            "off" -> Runahead.FRAMES_OFF
            "1" -> Runahead.FRAMES_1
            "2" -> Runahead.FRAMES_2
            else -> null
        }
    }

    /** Store (or clear, with null) this game's run-ahead override. */
    fun setRunaheadOverride(mainFile: String, frames: Int?) {
        store.put(key(mainFile, KEY_RUNAHEAD), frames?.let { Runahead.store(it) } ?: "")
    }

    // 2.0, feature 2 — per-game control-layout overrides. The
    // values are read with the same bounds ControlSettings writes
    // with (positions 0.02..0.98, scales 0.7..1.5, offsets ±500dp),
    // so a stored value that fails validation reads back as unset
    // and can never place a control off-screen.

    /** This game's stored stick-position override, or null when unset or invalid. */
    fun stickPositionOverride(mainFile: String): ControlPosition? =
        readPosition(mainFile, KEY_CONTROLS_STICK_POS)

    /** Store (or clear, with null) this game's stick-position override. */
    fun setStickPositionOverride(mainFile: String, pos: ControlPosition?) {
        store.put(key(mainFile, KEY_CONTROLS_STICK_POS), pos?.let { writePosition(it) } ?: "")
    }

    /** This game's stored button-cluster-position override, or null when unset or invalid. */
    fun buttonsPositionOverride(mainFile: String): ControlPosition? =
        readPosition(mainFile, KEY_CONTROLS_BUTTONS_POS)

    /** Store (or clear, with null) this game's button-cluster-position override. */
    fun setButtonsPositionOverride(mainFile: String, pos: ControlPosition?) {
        store.put(key(mainFile, KEY_CONTROLS_BUTTONS_POS), pos?.let { writePosition(it) } ?: "")
    }

    /** This game's stored stick-scale override, or null when unset or invalid. */
    fun stickScaleOverride(mainFile: String): Float? =
        readScale(mainFile, KEY_CONTROLS_STICK_SCALE)

    /** Store (or clear, with null) this game's stick-scale override. */
    fun setStickScaleOverride(mainFile: String, scale: Float?) {
        store.put(
            key(mainFile, KEY_CONTROLS_STICK_SCALE),
            scale?.coerceIn(ControlSettings.MIN_GROUP_SCALE, ControlSettings.MAX_GROUP_SCALE)?.toString() ?: "",
        )
    }

    /** This game's stored button-cluster-scale override, or null when unset or invalid. */
    fun buttonsScaleOverride(mainFile: String): Float? =
        readScale(mainFile, KEY_CONTROLS_BUTTONS_SCALE)

    /** Store (or clear, with null) this game's button-cluster-scale override. */
    fun setButtonsScaleOverride(mainFile: String, scale: Float?) {
        store.put(
            key(mainFile, KEY_CONTROLS_BUTTONS_SCALE),
            scale?.coerceIn(ControlSettings.MIN_GROUP_SCALE, ControlSettings.MAX_GROUP_SCALE)?.toString() ?: "",
        )
    }

    /** This game's stored D-pad-scale override, or null when unset or invalid. */
    fun dpadScaleOverride(mainFile: String): Float? =
        readScale(mainFile, KEY_CONTROLS_DPAD_SCALE)

    /** Store (or clear, with null) this game's D-pad-scale override. */
    fun setDpadScaleOverride(mainFile: String, scale: Float?) {
        store.put(
            key(mainFile, KEY_CONTROLS_DPAD_SCALE),
            scale?.coerceIn(ControlSettings.MIN_GROUP_SCALE, ControlSettings.MAX_GROUP_SCALE)?.toString() ?: "",
        )
    }

    /** This game's stored size-multiplier override for one button, or null when unset or invalid. */
    fun buttonSizeMultOverride(mainFile: String, id: String): Float? {
        if (id !in ControlSettings.BUTTON_IDS) return null
        return readScale(mainFile, "$KEY_CONTROLS_BUTTON_SIZE_PREFIX$id")
    }

    /** Store (or clear, with null) this game's size-multiplier override for one button. Unknown ids are ignored. */
    fun setButtonSizeMultOverride(mainFile: String, id: String, mult: Float?) {
        if (id !in ControlSettings.BUTTON_IDS) return
        store.put(
            key(mainFile, "$KEY_CONTROLS_BUTTON_SIZE_PREFIX$id"),
            mult?.coerceIn(ControlSettings.MIN_GROUP_SCALE, ControlSettings.MAX_GROUP_SCALE)?.toString() ?: "",
        )
    }

    /** All stored per-button size-multiplier overrides for this game, by button id. */
    fun buttonSizeMultsOverride(mainFile: String): Map<String, Float> =
        ControlSettings.BUTTON_IDS.mapNotNull { id ->
            buttonSizeMultOverride(mainFile, id)?.let { id to it }
        }.toMap()

    /** This game's stored offset override for one button, or null when unset or invalid. */
    fun buttonOffsetOverride(mainFile: String, id: String): Pair<Float, Float>? {
        if (id !in ControlSettings.BUTTON_IDS) return null
        return readOffset(mainFile, "$KEY_CONTROLS_BUTTON_OFFSET_PREFIX$id")
    }

    /** Store (or clear, with null) this game's offset override for one button. Unknown ids are ignored. */
    fun setButtonOffsetOverride(mainFile: String, id: String, offset: Pair<Float, Float>?) {
        if (id !in ControlSettings.BUTTON_IDS) return
        store.put(
            key(mainFile, "$KEY_CONTROLS_BUTTON_OFFSET_PREFIX$id"),
            offset?.let {
                "${it.first.coerceIn(-ControlSettings.MAX_BUTTON_OFFSET_DP, ControlSettings.MAX_BUTTON_OFFSET_DP)}," +
                    it.second.coerceIn(-ControlSettings.MAX_BUTTON_OFFSET_DP, ControlSettings.MAX_BUTTON_OFFSET_DP)
            } ?: "",
        )
    }

    /** All stored per-button offset overrides for this game, by button id. */
    fun buttonOffsetsOverride(mainFile: String): Map<String, Pair<Float, Float>> =
        ControlSettings.BUTTON_IDS.mapNotNull { id ->
            buttonOffsetOverride(mainFile, id)?.let { id to it }
        }.toMap()

    /** True when this game has any control-layout override saved. A stored value that fails validation reads as unset, so it does not count. */
    fun hasControlLayoutOverride(mainFile: String): Boolean {
        if (stickPositionOverride(mainFile) != null) return true
        if (buttonsPositionOverride(mainFile) != null) return true
        if (stickScaleOverride(mainFile) != null) return true
        if (buttonsScaleOverride(mainFile) != null) return true
        if (dpadScaleOverride(mainFile) != null) return true
        if (buttonSizeMultsOverride(mainFile).isNotEmpty()) return true
        if (buttonOffsetsOverride(mainFile).isNotEmpty()) return true
        return false
    }

    /**
     * Drop this game's control-layout overrides only — it follows
     * the global layout again. The rest of the profile (core,
     * shader, left control, run-ahead) is untouched.
     */
    fun clearControlLayout(mainFile: String) {
        for (suffix in CONTROL_LAYOUT_SUFFIXES) store.put(key(mainFile, suffix), "")
        for (id in ControlSettings.BUTTON_IDS) {
            store.put(key(mainFile, "$KEY_CONTROLS_BUTTON_SIZE_PREFIX$id"), "")
            store.put(key(mainFile, "$KEY_CONTROLS_BUTTON_OFFSET_PREFIX$id"), "")
        }
    }

    /** The stick position's effective value for this game: the override wins, otherwise the global value. */
    fun resolveStickPosition(mainFile: String, global: ControlPosition): ControlPosition =
        stickPositionOverride(mainFile) ?: global

    /** The button-cluster position's effective value for this game: the override wins, otherwise the global value. */
    fun resolveButtonsPosition(mainFile: String, global: ControlPosition): ControlPosition =
        buttonsPositionOverride(mainFile) ?: global

    /** The stick scale's effective value for this game: the override wins, otherwise the global value. */
    fun resolveStickScale(mainFile: String, global: Float): Float =
        stickScaleOverride(mainFile) ?: global

    /** The button-cluster scale's effective value for this game: the override wins, otherwise the global value. */
    fun resolveButtonsScale(mainFile: String, global: Float): Float =
        buttonsScaleOverride(mainFile) ?: global

    /**
     * The D-pad scale's effective value for this game: the D-pad
     * override wins, then the game's stick-scale override (mirroring
     * ControlSettings, where the D-pad falls back to the stick's
     * scale when never set), otherwise the global value.
     */
    fun resolveDpadScale(mainFile: String, global: Float): Float =
        dpadScaleOverride(mainFile) ?: stickScaleOverride(mainFile) ?: global

    /** The per-button size multipliers' effective values for this game: each button's override wins over the global value for that button. */
    fun resolveButtonSizeMults(mainFile: String, global: Map<String, Float>): Map<String, Float> {
        val result = global.toMutableMap()
        result.putAll(buttonSizeMultsOverride(mainFile))
        return result
    }

    /** The per-button offsets' effective values for this game: each button's override wins over the global value for that button. */
    fun resolveButtonOffsets(mainFile: String, global: Map<String, Pair<Float, Float>>): Map<String, Pair<Float, Float>> {
        val result = global.toMutableMap()
        result.putAll(buttonOffsetsOverride(mainFile))
        return result
    }

    /** True when this game has at least one override that changes anything. */
    fun hasProfile(mainFile: String): Boolean {
        if (CORE_KEYS.any { coreOverride(mainFile, it) != null }) return true
        if (shaderOverride(mainFile) != null) return true
        if (curvatureOverride(mainFile) != null) return true
        if (leftControlOverride(mainFile) != null) return true
        if (runaheadOverride(mainFile) != null) return true
        if (hasControlLayoutOverride(mainFile)) return true
        return false
    }

    /** Drop every override for this game — it follows the global settings again. */
    fun clear(mainFile: String) {
        for (suffix in ALL_SUFFIXES) store.put(key(mainFile, suffix), "")
        // The per-button layout keys are per id, not in ALL_SUFFIXES.
        for (id in ControlSettings.BUTTON_IDS) {
            store.put(key(mainFile, "$KEY_CONTROLS_BUTTON_SIZE_PREFIX$id"), "")
            store.put(key(mainFile, "$KEY_CONTROLS_BUTTON_OFFSET_PREFIX$id"), "")
        }
    }

    /** A core option's effective value for this game: the override wins, otherwise the global value. */
    fun resolveCore(mainFile: String, key: String, global: String): String =
        coreOverride(mainFile, key) ?: global

    /** The CRT shader's effective value for this game: the override wins, otherwise the global value. */
    fun resolveShader(mainFile: String, global: Boolean): Boolean =
        shaderOverride(mainFile) ?: global

    /** Curvature's effective value for this game: the override wins, otherwise the global value. */
    fun resolveCurvature(mainFile: String, global: Boolean): Boolean =
        curvatureOverride(mainFile) ?: global

    /** The left control's effective value for this game: the override wins, otherwise the global value. */
    fun resolveLeftControl(mainFile: String, global: LeftControl): LeftControl =
        leftControlOverride(mainFile) ?: global

    /** Run-ahead's effective value for this game: the override wins, otherwise the global value. */
    fun resolveRunahead(mainFile: String, global: Int): Int =
        runaheadOverride(mainFile) ?: global

    /**
     * Push this game's stored core overrides into the bridge, on
     * top of the global values CoreSettings.applyAll already pushed
     * (call before a game starts, after applyAll/applyMode).
     */
    fun applyCoreOverrides(entry: GameEntry) {
        if (!Bridge.ensureLoaded()) return
        for (key in CORE_KEYS) {
            val value = coreOverride(entry.mainFile, key) ?: continue
            Bridge.nativeSetOption(key, value)
        }
    }
}
