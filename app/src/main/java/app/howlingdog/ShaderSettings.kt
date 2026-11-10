package app.howlingdog

/**
 * The CRT display settings (shader chain on/off, screen
 * curvature on/off), stored in the same SharedPreferences file
 * as the core options but under their own "display.*" keys.
 * Values are validated on read, so a bad or missing value
 * always falls back to the default instead of breaking the
 * picture.
 *
 * Resolution (Mariner's chain): a per-system key wins; then the
 * shared master key; then the preset's own default. The UI only
 * exposes the two master toggles — the per-system keys exist so
 * a system can be switched on its own without restructuring.
 * The shader defaults ON (the era look is the point of the
 * chains); curvature defaults to each preset's own curveOn.
 * Both take effect the next time a game starts, like the
 * "Next game" core options.
 */
class ShaderSettings(private val store: OptionStore) {

    companion object {
        const val KEY_SHADER_MASTER = "display.shader" // "on" / "off"
        const val KEY_CURVE_MASTER = "display.curvature" // "on" / "off"
        const val KEY_SHADER_PREFIX = "display.shader." // + mode
        const val KEY_CURVE_PREFIX = "display.curvature." // + mode
    }

    /** Whether the CRT shader chain is on for a system mode. */
    fun shaderOn(mode: String): Boolean {
        return readToggle(KEY_SHADER_PREFIX + mode, KEY_SHADER_MASTER, true)
    }

    fun setShaderOn(mode: String, on: Boolean) {
        store.put(KEY_SHADER_PREFIX + mode, if (on) "on" else "off")
    }

    fun setShaderMaster(on: Boolean) {
        store.put(KEY_SHADER_MASTER, if (on) "on" else "off")
    }

    /** The master shader toggle itself (default on). */
    fun shaderMasterOn(): Boolean {
        return when (store.get(KEY_SHADER_MASTER)?.lowercase()) {
            "off" -> false
            else -> true
        }
    }

    /** Whether screen curvature is on for a system mode. */
    fun curvatureOn(mode: String): Boolean {
        return readToggle(
            KEY_CURVE_PREFIX + mode,
            KEY_CURVE_MASTER,
            ShaderPresets.forMode(mode).curveOn,
        )
    }

    fun setCurvatureOn(mode: String, on: Boolean) {
        store.put(KEY_CURVE_PREFIX + mode, if (on) "on" else "off")
    }

    fun setCurvatureMaster(on: Boolean) {
        store.put(KEY_CURVE_MASTER, if (on) "on" else "off")
    }

    /** The master curvature toggle itself (default on). */
    fun curvatureMasterOn(): Boolean {
        return when (store.get(KEY_CURVE_MASTER)?.lowercase()) {
            "off" -> false
            else -> true
        }
    }

    /** Per-system key wins, then the master key, then the default. Anything but "off" counts as on. */
    private fun readToggle(perKey: String, masterKey: String, default: Boolean): Boolean {
        store.get(perKey)?.lowercase()?.let { return it != "off" }
        store.get(masterKey)?.lowercase()?.let { return it != "off" }
        return default
    }
}
