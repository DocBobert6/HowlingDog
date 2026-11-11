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

        /** Every suffix a profile may store (core keys + display + control + gameplay). */
        val ALL_SUFFIXES: List<String> = CORE_KEYS + listOf(
            KEY_SHADER,
            KEY_CURVATURE,
            KEY_LEFT_CONTROL,
            KEY_RUNAHEAD,
        )
    }

    private fun key(mainFile: String, suffix: String) = "$PREFIX$mainFile.$suffix"

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

    /** True when this game has at least one override that changes anything. */
    fun hasProfile(mainFile: String): Boolean {
        if (CORE_KEYS.any { coreOverride(mainFile, it) != null }) return true
        if (shaderOverride(mainFile) != null) return true
        if (curvatureOverride(mainFile) != null) return true
        if (leftControlOverride(mainFile) != null) return true
        if (runaheadOverride(mainFile) != null) return true
        return false
    }

    /** Drop every override for this game — it follows the global settings again. */
    fun clear(mainFile: String) {
        for (suffix in ALL_SUFFIXES) store.put(key(mainFile, suffix), "")
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
